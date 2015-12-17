/*
 * This file is part of FastClasspathScanner.
 * 
 * Author: Luke Hutchison <luke .dot. hutch .at. gmail .dot. com>
 * 
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 * 
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Luke Hutchison
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.lukehutch.fastclasspathscanner.classgraph;

import io.github.lukehutch.fastclasspathscanner.utils.LazyMap;
import io.github.lukehutch.fastclasspathscanner.utils.MultiSet;
import io.github.lukehutch.fastclasspathscanner.utils.Utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class ClassGraphBuilder {
    ArrayList<DAGNode> standardClassNodes = new ArrayList<>();
    ArrayList<DAGNode> interfaceNodes = new ArrayList<>();
    ArrayList<DAGNode> annotationNodes = new ArrayList<>();

    public ClassGraphBuilder(final Collection<ClassInfo> classInfoFromScan) {
        // Take care of Scala quirks
        final ArrayList<ClassInfo> allClassInfo = new ArrayList<>(Utils.mergeScalaAuxClasses(classInfoFromScan));

        // Build class graph:

        // Create DAG node for each class
        final HashMap<String, DAGNode> classNameToDAGNode = new HashMap<>();
        for (final ClassInfo classInfo : allClassInfo) {
            final String className = classInfo.className;
            classNameToDAGNode.put(className, new DAGNode(className));
        }

        // Connect DAG nodes based on class connectivity
        for (final ClassInfo classInfo : allClassInfo) {
            final String className = classInfo.className;
            final DAGNode classNode = classNameToDAGNode.get(className);
            (classInfo.isAnnotation ? annotationNodes : classInfo.isInterface ? interfaceNodes : standardClassNodes)
                    .add(classNode);

            // Connect classes to the interfaces they implement, and interfaces to their superinterfaces
            if (classInfo.interfaceNames != null) {
                for (final String interfaceName : classInfo.interfaceNames) {
                    final DAGNode interfaceNode = classNameToDAGNode.get(interfaceName);
                    if (interfaceNode != null) {
                        if (!classInfo.isAnnotation && !classInfo.isInterface) {
                            // Cross-link standard classes to the interfaces they implement
                            classNode.addCrossLink(interfaceNode);
                        } else if (classInfo.isInterface) {
                            // If the class implementing the interface is itself an interface,
                            // then it is a subinterface of the implemented interface.
                            interfaceNode.addSubNode(classNode);
                        }
                    }
                }
            }

            // Connect classes to their superclass
            // (there should only be one superclass after handling the Scala quirks above)
            for (final String superclassName : classInfo.superclassNames) {
                final DAGNode superclassNode = classNameToDAGNode.get(superclassName);
                if (superclassNode != null) {
                    superclassNode.addSubNode(classNode);
                }
            }

            if (classInfo.annotationNames != null) {
                for (final String annotationName : classInfo.annotationNames) {
                    final DAGNode annotationNode = classNameToDAGNode.get(annotationName);
                    if (annotationNode != null) {
                        if (classInfo.isAnnotation) {
                            // If this class is an annotation, then its annotations are meta-annotations
                            annotationNode.addSubNode(classNode);
                        } else {
                            // For regular classes, add annotations as cross-links.
                            annotationNode.addCrossLink(classNode);
                        }
                    }
                }
            }
        }

        // Find transitive closure of DAG nodes for each of the three class types
        DAGNode.findTransitiveClosure(standardClassNodes);
        DAGNode.findTransitiveClosure(interfaceNodes);
        DAGNode.findTransitiveClosure(annotationNodes);
    }

    // -------------------------------------------------------------------------------------------------------------
    // DAGs

    /** A map from class name to the corresponding DAGNode object. */
    private final LazyMap<String, DAGNode> classNameToStandardClassNode = //
    new LazyMap<String, DAGNode>() {
        @Override
        public void initialize() {
            for (final DAGNode classNode : standardClassNodes) {
                map.put(classNode.name, classNode);
            }
        }
    };

    /** A map from interface name to the corresponding DAGNode object. */
    private final LazyMap<String, DAGNode> interfaceNameToInterfaceNode = //
    new LazyMap<String, DAGNode>() {
        @Override
        public void initialize() {
            for (final DAGNode interfaceNode : interfaceNodes) {
                map.put(interfaceNode.name, interfaceNode);
            }
        }
    };

    /** A map from annotation name to the corresponding DAGNode object. */
    private final LazyMap<String, DAGNode> annotationNameToAnnotationNode = //
    new LazyMap<String, DAGNode>() {
        @Override
        public void initialize() {
            for (final DAGNode annotationNode : annotationNodes) {
                map.put(annotationNode.name, annotationNode);
            }
        }
    };

    // -------------------------------------------------------------------------------------------------------------
    // Classes

    /** The sorted unique names of all classes, interfaces and annotations found during the scan. */
    private final LazyMap<String, ArrayList<String>> namesOfAllClasses = //
    new LazyMap<String, ArrayList<String>>() {
        @Override
        protected ArrayList<String> generateValue(final String ignored) {
            // Return same value for all keys -- just always use the key "" to fetch the list so that
            // work is not duplicated if you call twice with different keys.
            return Utils.sortedCopy(classNameToStandardClassNode.keySet(), interfaceNameToInterfaceNode.keySet(),
                    annotationNameToAnnotationNode.keySet());
        };
    };

    /**
     * The sorted unique names of all standard classes (non-interface, non-annotation classes) found during the
     * scan.
     */
    private final LazyMap<String, ArrayList<String>> namesOfAllStandardClasses = //
    new LazyMap<String, ArrayList<String>>() {
        @Override
        protected ArrayList<String> generateValue(final String ignored) {
            // Return same value for all keys -- just always use the key "" to fetch the list so that
            // work is not duplicated if you call twice with different keys.
            return Utils.sortedCopy(classNameToStandardClassNode.keySet());
        };
    };

    /** The sorted unique names of all interfaces found during the scan. */
    private final LazyMap<String, ArrayList<String>> namesOfAllInterfaceClasses = //
    new LazyMap<String, ArrayList<String>>() {
        @Override
        protected ArrayList<String> generateValue(final String ignored) {
            // Return same value for all keys -- just always use the key "" to fetch the list so that
            // work is not duplicated if you call twice with different keys.
            return Utils.sortedCopy(interfaceNameToInterfaceNode.keySet());
        };
    };

    /** The sorted unique names of all annotation classes found during the scan. */
    private final LazyMap<String, ArrayList<String>> namesOfAllAnnotationClasses = //
    new LazyMap<String, ArrayList<String>>() {
        @Override
        protected ArrayList<String> generateValue(final String ignored) {
            // Return same value for all keys -- just always use the key "" to fetch the list so that
            // work is not duplicated if you call twice with different keys.
            return Utils.sortedCopy(annotationNameToAnnotationNode.keySet());
        };
    };

    /**
     * Return the sorted unique names of all classes, interfaces and annotations found during the scan.
     */
    public List<String> getNamesOfAllClasses() {
        return namesOfAllClasses.get("");
    }

    /**
     * Return the sorted unique names of all standard classes (non-interface, non-annotation classes) found during
     * the scan.
     */
    public List<String> getNamesOfAllStandardClasses() {
        return namesOfAllStandardClasses.get("");
    }

    /** Return the sorted unique names of all interface classes found during the scan. */
    public List<String> getNamesOfAllInterfaceClasses() {
        return namesOfAllInterfaceClasses.get("");
    }

    /**
     * Return the sorted unique names of all annotation classes found during the scan.
     */
    public List<String> getNamesOfAllAnnotationClasses() {
        return namesOfAllAnnotationClasses.get("");
    }

    /** Return the sorted list of names of all subclasses of the named class. */
    private final LazyMap<String, ArrayList<String>> classNameToSubclassNames = //
    new LazyMap<String, ArrayList<String>>() {
        @Override
        protected ArrayList<String> generateValue(final String className) {
            final DAGNode classNode = classNameToStandardClassNode.get(className);
            if (classNode == null) {
                return null;
            }
            final ArrayList<String> subclasses = new ArrayList<>(classNode.allSubNodes.size());
            for (final DAGNode subNode : classNode.allSubNodes) {
                subclasses.add(subNode.name);
            }
            Collections.sort(subclasses);
            return subclasses;
        };
    };

    /** Return the sorted list of names of all subclasses of the named class. */
    public List<String> getNamesOfSubclassesOf(final String className) {
        final ArrayList<String> subclassNames = classNameToSubclassNames.get(className);
        if (subclassNames == null) {
            return Collections.emptyList();
        } else {
            return subclassNames;
        }
    }

    /** Return the sorted list of names of all superclasses of the named class. */
    private final LazyMap<String, ArrayList<String>> classNameToSuperclassNames = //
    new LazyMap<String, ArrayList<String>>() {
        @Override
        protected ArrayList<String> generateValue(final String className) {
            final DAGNode classNode = classNameToStandardClassNode.get(className);
            if (classNode == null) {
                return null;
            }
            final ArrayList<String> superclasses = new ArrayList<>(classNode.allSuperNodes.size());
            for (final DAGNode superNode : classNode.allSuperNodes) {
                superclasses.add(superNode.name);
            }
            Collections.sort(superclasses);
            return superclasses;
        };
    };

    /** Return the sorted list of names of all superclasses of the named class. */
    public List<String> getNamesOfSuperclassesOf(final String className) {
        final ArrayList<String> superclassNames = classNameToSuperclassNames.get(className);
        if (superclassNames == null) {
            return Collections.emptyList();
        } else {
            return superclassNames;
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Interfaces

    /** Return the sorted list of names of all subinterfaces of the named interface. */
    private final LazyMap<String, ArrayList<String>> interfaceNameToSubinterfaceNames = //
    new LazyMap<String, ArrayList<String>>() {
        @Override
        protected ArrayList<String> generateValue(final String interfaceName) {
            final DAGNode interfaceNode = interfaceNameToInterfaceNode.get(interfaceName);
            if (interfaceNode == null) {
                return null;
            }
            final ArrayList<String> subinterfaces = new ArrayList<>(interfaceNode.allSubNodes.size());
            for (final DAGNode subNode : interfaceNode.allSubNodes) {
                subinterfaces.add(subNode.name);
            }
            Collections.sort(subinterfaces);
            return subinterfaces;
        };
    };

    /** Return the sorted list of names of all subinterfaces of the named interface. */
    public List<String> getNamesOfSubinterfacesOf(final String interfaceName) {
        final ArrayList<String> subinterfaceNames = interfaceNameToSubinterfaceNames.get(interfaceName);
        if (subinterfaceNames == null) {
            return Collections.emptyList();
        } else {
            return subinterfaceNames;
        }
    }

    /** Return the sorted list of names of all superinterfaces of the named interface. */
    private final LazyMap<String, ArrayList<String>> interfaceNameToSuperinterfaceNames = //
    new LazyMap<String, ArrayList<String>>() {
        @Override
        protected ArrayList<String> generateValue(final String interfaceName) {
            final DAGNode interfaceNode = interfaceNameToInterfaceNode.get(interfaceName);
            if (interfaceNode == null) {
                return null;
            }
            final ArrayList<String> superinterfaces = new ArrayList<>(interfaceNode.allSuperNodes.size());
            for (final DAGNode superNode : interfaceNode.allSuperNodes) {
                superinterfaces.add(superNode.name);
            }
            Collections.sort(superinterfaces);
            return superinterfaces;
        };
    };

    /** Return the names of all superinterfaces of the named interface. */
    public List<String> getNamesOfSuperinterfacesOf(final String interfaceName) {
        final ArrayList<String> superinterfaceNames = interfaceNameToSuperinterfaceNames.get(interfaceName);
        if (superinterfaceNames == null) {
            return Collections.emptyList();
        } else {
            return superinterfaceNames;
        }
    }

    /** Mapping from interface names to the set of names of classes that implement the interface. */
    private final LazyMap<String, HashSet<String>> interfaceNameToClassNamesSet = //
    new LazyMap<String, HashSet<String>>() {
        @Override
        public void initialize() {
            // Create mapping from interface names to the names of classes that implement the interface.
            for (final DAGNode classNode : classNameToStandardClassNode.values()) {
                // For regular classes, cross-linked class names are the names of implemented interfaces.
                // Create reverse mapping from interfaces and superinterfaces implemented by the class
                // back to the class the interface implements
                final ArrayList<DAGNode> interfaceNodes = classNode.crossLinkedNodes;
                for (final DAGNode interfaceNode : interfaceNodes) {
                    // Map from interface to implementing class
                    MultiSet.put(map, interfaceNode.name, classNode.name);
                    // Classes that subclass another class that implements an interface
                    // also implement the same interface.
                    for (final DAGNode subclassNode : classNode.allSubNodes) {
                        MultiSet.put(map, interfaceNode.name, subclassNode.name);
                    }

                    // Do the same for any superinterfaces of this interface: any class that
                    // implements an interface also implements all its superinterfaces, and so
                    // do all the subclasses of the class.
                    for (final DAGNode superinterfaceNode : interfaceNode.allSuperNodes) {
                        MultiSet.put(map, superinterfaceNode.name, classNode.name);
                        for (final DAGNode subclassNode : classNode.allSubNodes) {
                            MultiSet.put(map, superinterfaceNode.name, subclassNode.name);
                        }
                    }
                }
            }
        }
    };

    /** Mapping from interface names to the sorted list of unique names of classes that implement the interface. */
    private final LazyMap<String, ArrayList<String>> interfaceNameToClassNames = //
    LazyMap.convertToMultiMapSorted(interfaceNameToClassNamesSet);

    /** Return the sorted list of names of all classes implementing the named interface. */
    public List<String> getNamesOfClassesImplementing(final String interfaceName) {
        final ArrayList<String> classes = interfaceNameToClassNames.get(interfaceName);
        if (classes == null) {
            return Collections.emptyList();
        } else {
            return classes;
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Annotations

    /** A MultiSet mapping from annotation name to the set of names of the classes they annotate. */
    private final LazyMap<String, HashSet<String>> annotationNameToAnnotatedClassNamesSet = //
    new LazyMap<String, HashSet<String>>() {
        @Override
        protected HashSet<String> generateValue(final String annotationName) {
            final DAGNode annotationNode = annotationNameToAnnotationNode.get(annotationName);
            if (annotationNode == null) {
                return null;
            }
            final HashSet<String> classNames = new HashSet<>();
            for (final DAGNode subNode : annotationNode.allSubNodes) {
                for (final DAGNode crossLinkedNode : subNode.crossLinkedNodes) {
                    classNames.add(crossLinkedNode.name);
                }
            }
            for (final DAGNode crossLinkedNode : annotationNode.crossLinkedNodes) {
                classNames.add(crossLinkedNode.name);
            }
            return classNames;
        };
    };

    /** A MultiMap mapping from annotation name to the sorted list of names of the classes they annotate. */
    private final LazyMap<String, ArrayList<String>> annotationNameToAnnotatedClassNames = LazyMap
            .convertToMultiMapSorted(annotationNameToAnnotatedClassNamesSet);

    /** Return the sorted list of names of all classes with the named class annotation or meta-annotation. */
    public List<String> getNamesOfClassesWithAnnotation(final String annotationName) {
        final ArrayList<String> classNames = annotationNameToAnnotatedClassNames.get(annotationName);
        if (classNames == null) {
            return Collections.emptyList();
        } else {
            return classNames;
        }
    }

    /**
     * A map from the names of classes to the sorted list of names of annotations and meta-annotations on the
     * classes.
     */
    private final LazyMap<String, ArrayList<String>> classNameToAnnotationNames = //
    LazyMap.convertToMultiMapSorted( //
    LazyMap.invertMultiSet(annotationNameToAnnotatedClassNamesSet, annotationNameToAnnotationNode));

    /** Return the sorted list of names of all annotations and meta-annotations on the named class. */
    public List<String> getNamesOfAnnotationsOnClass(final String classOrInterfaceName) {
        final ArrayList<String> annotationNames = classNameToAnnotationNames.get(classOrInterfaceName);
        if (annotationNames == null) {
            return Collections.emptyList();
        } else {
            return annotationNames;
        }
    }

    /** A map from meta-annotation name to the set of names of the annotations they annotate. */
    private final LazyMap<String, HashSet<String>> metaAnnotationNameToAnnotatedAnnotationNamesSet = //
    new LazyMap<String, HashSet<String>>() {
        @Override
        protected HashSet<String> generateValue(final String annotationName) {
            final DAGNode annotationNode = annotationNameToAnnotationNode.get(annotationName);
            if (annotationNode == null) {
                return null;
            }
            final HashSet<String> subNodes = new HashSet<>();
            for (final DAGNode subNode : annotationNode.allSubNodes) {
                subNodes.add(subNode.name);
            }
            return subNodes;
        }
    };

    /**
     * Mapping from annotation name to the sorted list of names of annotations and meta-annotations on the
     * annotation.
     */
    private final LazyMap<String, ArrayList<String>> annotationNameToMetaAnnotationNames = //
    LazyMap.convertToMultiMapSorted( //
    LazyMap.invertMultiSet(metaAnnotationNameToAnnotatedAnnotationNamesSet, annotationNameToAnnotationNode));

    /** Return the sorted list of names of all meta-annotations on the named annotation. */
    public List<String> getNamesOfMetaAnnotationsOnAnnotation(final String annotationName) {
        final ArrayList<String> metaAnnotationNames = annotationNameToMetaAnnotationNames.get(annotationName);
        if (metaAnnotationNames == null) {
            return Collections.emptyList();
        } else {
            return metaAnnotationNames;
        }
    }

    /** Mapping from meta-annotation names to the sorted list of names of annotations that have the meta-annotation. */
    private final LazyMap<String, ArrayList<String>> metaAnnotationNameToAnnotatedAnnotationNames = //
    LazyMap.convertToMultiMapSorted(metaAnnotationNameToAnnotatedAnnotationNamesSet);

    /** Return the names of all annotations that have the named meta-annotation. */
    public List<String> getNamesOfAnnotationsWithMetaAnnotation(final String metaAnnotationName) {
        final ArrayList<String> annotationNames = metaAnnotationNameToAnnotatedAnnotationNames
                .get(metaAnnotationName);
        if (annotationNames == null) {
            return Collections.emptyList();
        } else {
            return annotationNames;
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Class graph visualization

    /**
     * Splits a .dot node label into two text lines, putting the package on one line and the class name on the next.
     */
    private static String label(DAGNode node) {
        String className = node.name;
        int dotIdx = className.lastIndexOf('.');
        if (dotIdx < 0) {
            return className;
        }
        return className.substring(0, dotIdx + 1) + "\\n" + className.substring(dotIdx + 1);
    }

    /**
     * Generates a .dot file which can be fed into GraphViz for layout and visualization of the class graph.
     */
    public String generateClassGraphDotFile() {
        StringBuilder buf = new StringBuilder();
        buf.append("digraph {\n");
        buf.append("size=\"400,400\";\n");
        buf.append("layout=neato;\n");
        buf.append("overlap=false;\n");
        buf.append("splines=true;\n");
        buf.append("pack=true;\n");
        buf.append("start=\"random\";\n");
        buf.append("sep=0.1;\n");
        buf.append("edge[len=2];\n");

        buf.append("\nnode[shape=box,style=filled,fillcolor=\"#eeeeaa\"];\n");
        for (DAGNode node : standardClassNodes) {
            buf.append("  \"" + label(node) + "\"\n");
        }

        buf.append("\nnode[shape=diamond,style=filled,fillcolor=\"#aaeeee\"];\n");
        for (DAGNode node : interfaceNodes) {
            buf.append("  \"" + label(node) + "\"\n");
        }

        buf.append("\nnode[shape=oval,style=filled,fillcolor=\"#eeaaee\"];\n");
        for (DAGNode node : annotationNodes) {
            buf.append("  \"" + label(node) + "\"\n");
        }

        buf.append("\n");
        for (DAGNode classNode : standardClassNodes) {
            for (DAGNode superclassNode : classNode.directSuperNodes) {
                // class --> superclass
                buf.append("  \"" + label(classNode) + "\" -> \"" + label(superclassNode) + "\"\n");
            }
            for (DAGNode implementedInterfaceNode : classNode.crossLinkedNodes) {
                // class --<> implemented interface
                buf.append("  \"" + label(classNode) + "\" -> \"" + label(implementedInterfaceNode)
                        + "\" [arrowhead=odiamond]\n");
            }
        }
        for (DAGNode interfaceNode : interfaceNodes) {
            for (DAGNode superinterfaceNode : interfaceNode.directSuperNodes) {
                // interface --> superinterface
                buf.append("  \"" + label(interfaceNode) + "\" -> \"" + label(superinterfaceNode)
                        + "\" [arrowhead=diamond]\n");
            }
        }
        for (DAGNode annotationNode : annotationNodes) {
            for (DAGNode metaAnnotationNode : annotationNode.directSuperNodes) {
                // annotation --o meta-annotation
                buf.append("  \"" + label(annotationNode) + "\" -> \"" + label(metaAnnotationNode)
                        + "\" [arrowhead=dot]\n");
            }
            for (DAGNode annotatedClassNode : annotationNode.crossLinkedNodes) {
                // annotated class --o annotation
                buf.append("  \"" + label(annotatedClassNode) + "\" -> \"" + label(annotationNode)
                        + "\" [arrowhead=odot]\n");
            }
        }
        buf.append("}");
        return buf.toString();
    }
}
