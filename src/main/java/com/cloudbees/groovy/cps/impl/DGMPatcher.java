package com.cloudbees.groovy.cps.impl;

import com.cloudbees.groovy.cps.CpsDefaultGroovyMethods;
import com.google.common.base.Objects;
import groovy.lang.GroovySystem;
import groovy.lang.MetaClass;
import groovy.lang.MetaClassImpl;
import groovy.lang.MetaClassRegistry;
import groovy.lang.MetaMethod;
import org.codehaus.groovy.reflection.CachedClass;
import org.codehaus.groovy.reflection.CachedMethod;
import org.codehaus.groovy.reflection.ClassInfo;
import org.codehaus.groovy.reflection.GeneratedMetaMethod;
import org.codehaus.groovy.reflection.ReflectionCache;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.runtime.metaclass.MetaClassRegistryImpl;
import org.codehaus.groovy.runtime.metaclass.MetaMethodIndex;
import org.codehaus.groovy.runtime.metaclass.MetaMethodIndex.Entry;
import org.codehaus.groovy.runtime.metaclass.NewInstanceMetaMethod;
import org.codehaus.groovy.util.AbstractConcurrentMapBase;
import org.codehaus.groovy.util.AbstractConcurrentMapBase.Segment;
import org.codehaus.groovy.util.FastArray;
import org.codehaus.groovy.util.ManagedLinkedList;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Patches Groovy's method dispatch table so that they point to {@link CpsDefaultGroovyMethods} instead of
 * {@link DefaultGroovyMethods}.
 *
 * <p>
 * To be able to correctly execute code like {@code list.each ...} in CPS, we need to tweak Groovy
 * so that it dispatches methods like 'each' to {@link CpsDefaultGroovyMethods} instead of {@link DefaultGroovyMethods}.
 * Groovy has some fairly involved internal data structure to determine which method to dispatch, but
 * at high level, this comes down to the following:
 *
 * <ol>
 * <li>{@code ClassInfo.ClassInfoSet} (Groovy 1.x) holds references to {@link ClassInfo} where one instance exists for each {@link Class}
 * <li>{@link ClassInfo} holds a reference to {@link MetaClassImpl}
 * <li>{@link MetaClassImpl} holds a whole lot of {@link MetaMethod}s for methods that belong to the class
 * <li>Some of those {@link MetaMethod}s are {@link GeneratedMetaMethod} that points to methods defined on {@link DefaultGroovyMethods}
 * </ol>
 *
 * <p>
 * Many of these objects are created lazily and various caching is involved in various layers (such as
 * {@link MetaClassImpl#metaMethodIndex}) presumably to make the method dispatching more efficient.
 *
 * <p>
 * Our strategy here is to locate {@link GeneratedMetaMethod}s that point to {@link DefaultGroovyMethods}
 * and replace them by another {@link MetaMethod} that points to {@link CpsDefaultGroovyMethods}. Given
 * the elaborate data structure Groovy builds, we liberally walk the data structure and patch references
 * wherever we find them, instead of being precise & surgical about what we replace. This logic
 * is implemented in {@link #patch(Object)}.
 *
 *
 * <h2>How Groovy registers methods from {@link DefaultGroovyMethods}?</h2>
 * <p>
 * (This is a memo I took during this investigation, in case in the future this becomes useful again)
 * <p>
 * {@link DefaultGroovyMethods} are build-time processed (where?) to a series of "dgm" classes, and this gets
 * loaded into {@link MetaClass} structures in {@link GeneratedMetaMethod.DgmMethodRecord#loadDgmInfo()}.
 * <p>
 * The code above is called from {@link MetaClassRegistryImpl#MetaClassRegistryImpl(int,boolean)} , which
 * uses {@link CachedClass#setNewMopMethods(List)} to install method definitions from DGM.
 * {@link CachedClass#setNewMopMethods(List)}  internally calls into {@link CachedClass#updateSetNewMopMethods(List)},
 * which simply updates {@link ClassInfo#newMetaMethods}. This is where the method definitions stay for a while
 * <p>
 * The only read usage of {@link ClassInfo#newMetaMethods} is in {@link CachedClass#getNewMetaMethods()}, and
 * this method builds its return value from all the super types. This method is then further used by
 * {@link MetaClassImpl} when it is instantiated and build its own index.
 *
 * @author Kohsuke Kawaguchi
 */
class DGMPatcher {

    private static final Logger LOGGER = Logger.getLogger(DGMPatcher.class.getName());

    // we need to traverse various internal fields of the objects
    private final Field MetaClassImpl_myNewMetaMethods = field(MetaClassImpl.class,"myNewMetaMethods");
    private final Field MetaClassImpl_newGroovyMethodsSet = field(MetaClassImpl.class,"newGroovyMethodsSet");
    private final Field MetaClassImpl_metaMethodIndex = field(MetaClassImpl.class,"metaMethodIndex");
    private final Field ClassInfo_dgmMetaMethods = field(ClassInfo.class,"dgmMetaMethods");
    private final Field ClassInfo_newMetaMethods = field(ClassInfo.class,"newMetaMethods");
    private final Field ClassInfo_globalClassSet = field(ClassInfo.class,"globalClassSet");
    private final Field AbstractConcurrentMapBase_segments = field(AbstractConcurrentMapBase.class,"segments");
    private final Field Segment_table = field(Segment.class,"table");

    // in Groovy 1., ClassInfo.globalClassSet returns ClassInfoSet, which extends AbstractConcurrentMap
    // in Groovy 2.x, ClassInfo.globalClassSet return private GlobalClassSet, which defines
    // "ManagedLinkedList items" field.
    private final Class GlobalClassSet = clazz("org.codehaus.groovy.reflection.ClassInfo$GlobalClassSet");
    private final Field GlobalClassSet_items = field(GlobalClassSet,"items");

//    private final Field ManagedLinkedList_head = field(ManagedLinkedList.class,"head");
//    private final Class ManagedLinkedList_Element = clazz(ManagedLinkedList.class.getName()+".Element");
//    private final Field ManagedLinkedList_Element_next = field(ManagedLinkedList_Element,"next");
//
    /**
     * Used to compare two {@link MetaMethod} by their signatures.
     */
    static final class Key {
        /**
         * Receiver type.
         */
        final Class declaringClass;

        /**
         * Method name
         */
        final String name;

        /**
         * Method signature
         */
        final Class[] nativeParamTypes;

        Key(Class declaringClass, String name, Class[] nativeParamTypes) {
            this.declaringClass = declaringClass;
            this.name = name;
            this.nativeParamTypes = nativeParamTypes;
        }

        Key(MetaMethod m) {
            this(m.getDeclaringClass().getTheClass(), m.getName(), m.getNativeParameterTypes());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Key key = (Key) o;
            return Objects.equal(declaringClass, key.declaringClass) &&
                    Objects.equal(name, key.name) &&
                    Arrays.equals(nativeParamTypes, key.nativeParamTypes);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(declaringClass, name, Arrays.hashCode(nativeParamTypes));
        }

        @Override
        public String toString() {
            return declaringClass.getName() + "." + name + Arrays.toString(nativeParamTypes);
        }
    }

    /**
     * Methods defined in {@link CpsDefaultGroovyMethods} to override definitions in {@link DefaultGroovyMethods}.
     */
    private final Map<Key,MetaMethod> overrides = new HashMap<Key, MetaMethod>();

    /**
     * @param methods
     *      List of methods to overwrite {@link DefaultGroovyMethods}
     */
    DGMPatcher(List<MetaMethod> methods) {
        for (MetaMethod m : methods) {
            MetaMethod old = overrides.put(new Key(m),m);
            if (old != null) {
                throw new IllegalStateException("duplication between " + m + " and " + old);
            }
        }
    }

    /**
     * Visits Groovy data structure and install methods given in the constructor.
     */
    void patch() {
        MetaClassRegistry r = GroovySystem.getMetaClassRegistry();
// this never seems to iterate anything
//        Iterator<MetaClass> itr = r.iterator();
//        while (itr.hasNext()) {
//            MetaClass mc = itr.next();
//            patch(mc);
//        }
        patch(r);

        try {
            patch(ClassInfo_globalClassSet.get(null));
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Walks the given object recursively, patch references, and return the replacement object.
     *
     * <p>
     * Key data structure we visit is {@link MetaClassImpl},
     */
    private <T> T patch(T o) {
        if (o == null) {
            return null;
        }
        LOGGER.log(Level.FINE, "patching {0}", o.getClass().getName());
        if (o instanceof MetaClassRegistryImpl) {
            MetaClassRegistryImpl r = (MetaClassRegistryImpl) o;
            patch(r.getInstanceMethods());
            patch(r.getStaticMethods());
        } else
        if (isInstance(GlobalClassSet,o)) {
            patch(o,GlobalClassSet_items);
        } else
        // TODO this is redundant. Could simply iterate a collection of fields to see if o is assignable to the defining class.
        if (o instanceof AbstractConcurrentMapBase) {
            // discover all ClassInfo in ClassInfoSet via Segment -> table -> ClassInfo
            patch(o, AbstractConcurrentMapBase_segments);
        } else
        if (o instanceof ManagedLinkedList) {
            for (Iterator itr = ((ManagedLinkedList) o).iterator(); itr.hasNext(); ) {
                Object item = itr.next();
                if (patch(item)!=item) {
                    LOGGER.log(Level.FINE, "Can't replace members of ManagedLinkedList",item);
                }
            }
        } else
        if (o instanceof Segment) {
            Segment s = (Segment) o;
            patch(s,Segment_table);
        } else
        if (o instanceof ClassInfo) {
            ClassInfo ci = (ClassInfo) o;
            patch(ci,ClassInfo_dgmMetaMethods);
            patch(ci,ClassInfo_newMetaMethods);
            // ClassInfo -> MetaClass
            patch(ci.getStrongMetaClass());
            patch(ci.getWeakMetaClass());
//            patch(ci.getCachedClass());
        } else
// doesn't look like we need to visit this
//        if (o instanceof CachedClass) {
//            CachedClass cc = (CachedClass) o;
//            patch(cc.classInfo);
//        } else
        if (o instanceof MetaClassImpl) {
            MetaClassImpl mc = (MetaClassImpl) o;
            patch(mc,MetaClassImpl_myNewMetaMethods);
            patch(mc.getMethods()); // this directly returns mc.allMethods
            patch(mc,MetaClassImpl_newGroovyMethodsSet);
            patch(mc,MetaClassImpl_metaMethodIndex);
        } else
        if (o instanceof MetaMethodIndex) {
            MetaMethodIndex mmi = (MetaMethodIndex) o;
            for (Entry e : mmi.getTable()) {
                if (e!=null) {
                    e.methods = patch(e.methods);
                    e.methodsForSuper = patch(e.methodsForSuper);
                    e.staticMethods = patch(e.staticMethods);
                }
            }
            mmi.clearCaches(); // in case anything was actually modified
        } else
        if (o instanceof GeneratedMetaMethod) {
            // the actual patch logic.
            GeneratedMetaMethod gm = (GeneratedMetaMethod) o;
            MetaMethod replace = overrides.get(new Key(gm));
            if (replace!=null) {
                // we found a GeneratedMetaMethod that points to DGM that needs to be replaced!
                PATCH_COUNT++;
                System.out.println("Patched "+PATCH_COUNT);
                if (PATCH_COUNT==10) {
                    threadDump();
                }
                return (T)replace;
            }
        } else
// other collection structure that needs to be recursively visited
        if (o instanceof Object[]) {
            Object[] a = (Object[])o;
            for (int i=0; i<a.length; i++) {
                a[i] = patch(a[i]);
            }
        } else
        if (o instanceof List) {
            List l = (List)o;
            ListIterator i = l.listIterator();
            while (i.hasNext()) {
                Object x = i.next();
                Object y = patch(x);
                if (x!=y)   i.set(y);
            }
        } else
        if (o instanceof FastArray) {
            FastArray a = (FastArray) o;
            for (int i=0; i<a.size(); i++) {
                Object x = a.get(i);
                Object y = patch(x);
                if (x!=y)   a.set(i,y);
            }
        } else
        if (o instanceof Set) {
            Set s = (Set)o;
            for (Object x : s.toArray()) {
                Object y = patch(x);
                if (x!=y) {
                    s.remove(x);
                    s.add(y);
                }
            }
        }

        return o;
    }

    private boolean isInstance(Class t, Object o) {
        return t!=null && t.isInstance(o);
    }

    /**
     * Patch a field of an object that's not directly accessible.
     */
    private void patch(Object o, Field f) {
        if (f == null) {
            return; // unavailable
        }
        try {
            Object x = f.get(o);
            Object y = patch(x);
            if (x!=y)
                f.set(o,y);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e); // we make this field accessible
        }
    }

    private Class clazz(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException x) {
            LOGGER.log(Level.FINE, "no such class {0}", name);
            return null;
        }
    }

    private Field field(String owner, String field) {
        return field(clazz(owner), field);
    }

    private Field field(Class<?> owner, String field) {
        if (owner==null)    return null;
        try {
            Field f = owner.getDeclaredField(field);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            LOGGER.log(Level.FINE, "no such field {0} in {1}", new Object[] {field, owner.getName()});
            return null;
        }
    }

    static {
        List<MetaMethod> methods = new ArrayList<MetaMethod>();
        for (CachedMethod m : ReflectionCache.getCachedClass(CpsDefaultGroovyMethods.class).getMethods()) {
            if (m.isStatic() && m.isPublic()) {
                CachedClass[] paramTypes = m.getParameterTypes();
                if (paramTypes.length > 0) {
                    methods.add(new NewInstanceMetaMethod(m));
                }
            }
        }
        new DGMPatcher(methods).patch();
        LOGGER.log(Level.FINE, "patched {0}", methods);
    }

    /**
     * No-op method to ensure the static initializer has run.
     */
    public static void init() {}

    private static int PATCH_COUNT = 0;

    /**
     * Debug assistance: dump all the threads and report that to stdout
     */
    private static void threadDump() {
        ThreadMXBean mbean = ManagementFactory.getThreadMXBean();
        for (ThreadInfo ti : mbean.dumpAllThreads(mbean.isObjectMonitorUsageSupported(),mbean.isSynchronizerUsageSupported()))
            System.out.println(dumpThreadInfo(ti));
    }

    private static String dumpThreadInfo(ThreadInfo ti) {
        StringBuilder sb = new StringBuilder("\"" + ti.getThreadName() + "\"" +
                                             " Id=" + ti.getThreadId() + " Group=" +
                                             ti.getThreadState());
        if (ti.getLockName() != null) {
            sb.append(" on " + ti.getLockName());
        }
        if (ti.getLockOwnerName() != null) {
            sb.append(" owned by \"" + ti.getLockOwnerName() +
                      "\" Id=" + ti.getLockOwnerId());
        }
        if (ti.isSuspended()) {
            sb.append(" (suspended)");
        }
        if (ti.isInNative()) {
            sb.append(" (in native)");
        }
        sb.append('\n');
        StackTraceElement[] stackTrace = ti.getStackTrace();
        for (int i=0; i < stackTrace.length; i++) {
            StackTraceElement ste = stackTrace[i];
            sb.append("\tat ").append(ste);
            sb.append('\n');
            if (i == 0 && ti.getLockInfo() != null) {
                Thread.State ts = ti.getThreadState();
                switch (ts) {
                    case BLOCKED:
                        sb.append("\t-  blocked on ").append(ti.getLockInfo());
                        sb.append('\n');
                        break;
                    case WAITING:
                        sb.append("\t-  waiting on ").append(ti.getLockInfo());
                        sb.append('\n');
                        break;
                    case TIMED_WAITING:
                        sb.append("\t-  waiting on ").append(ti.getLockInfo());
                        sb.append('\n');
                        break;
                    default:
                }
            }

            for (MonitorInfo mi : ti.getLockedMonitors()) {
                if (mi.getLockedStackDepth() == i) {
                    sb.append("\t-  locked ").append(mi);
                    sb.append('\n');
                }
            }
       }

       LockInfo[] locks = ti.getLockedSynchronizers();
       if (locks.length > 0) {
           sb.append("\n\tNumber of locked synchronizers = " + locks.length);
           sb.append('\n');
           for (LockInfo li : locks) {
               sb.append("\t- ").append(li);
               sb.append('\n');
           }
       }
       sb.append('\n');
       return sb.toString();
    }
}
