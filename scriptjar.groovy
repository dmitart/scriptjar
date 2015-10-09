#!/usr/bin/env groovy

import groovy.grape.Grape
import org.codehaus.groovy.ast.*
import org.codehaus.groovy.ast.stmt.EmptyStatement
import org.codehaus.groovy.classgen.GeneratorContext
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilationUnit.PrimaryClassNodeOperation
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.tools.GroovyClass
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import static org.codehaus.groovy.control.Phases.*

class RemoveStaticPrimaryClassNodeOperation extends PrimaryClassNodeOperation {

    @Override
    void call(SourceUnit source44, GeneratorContext context, ClassNode classNode) throws CompilationFailedException {
        classNode.visitContents(new GroovyClassVisitor() {
            @Override
            void visitClass(ClassNode node) {
            }

            @Override
            void visitConstructor(ConstructorNode node) {
            }

            @Override
            void visitMethod(MethodNode node) {
                if (node.name == '<clinit>') {
                    node.setCode(new EmptyStatement())
                }
            }

            @Override
            void visitField(FieldNode node) {
            }

            @Override
            void visitProperty(PropertyNode node) {
            }
        })
    }
}

def compile(String prefix, File file) {
    CompilationUnit unit = new CompilationUnit()
    unit.addPhaseOperation(new RemoveStaticPrimaryClassNodeOperation(), SEMANTIC_ANALYSIS)
    unit.addSource(SourceUnit.create(prefix, file.text))
    unit.compile(CLASS_GENERATION)
    GroovyClass gc = unit.getClasses()[0]
    return gc.getBytes()
}

File getGroovyLib() {
    if (System.getenv('GROOVY_HOME')) {
        def libs = new File(System.getenv('GROOVY_HOME'), 'lib')
        def groovylib = libs.listFiles().find{it.name =~ /groovy-\d+.\d+.\d+.jar/}
        if (groovylib) {
            return groovylib
        } else {
            println "Cann't find Groovy lib in ${libs.absolutePath}, specify it manually as Grab dependency"
            System.exit(1)
        }
    } else {
        println "Cann't find GROOVY_HOME"
        System.exit(1)
    }
}

List dependencies(String name) {
    final GroovyClassLoader classLoader = new GroovyClassLoader()
    classLoader.parseClass(new File(name).text)
    def files = Grape.resolve([:], Grape.listDependencies(classLoader)).collect{ new JarFile(it.path) }
    files << new JarFile(getGroovyLib())
}

void createJar(String prefix, String name, List jars, byte[] compiled) {
    File output = new File(name)
    JarOutputStream jos = new JarOutputStream(new FileOutputStream(output))

    jos.putNextEntry(new JarEntry('META-INF/'))

    def manifestEntry = new JarEntry('META-INF/MANIFEST.MF')
    byte[] manifest = "Manifest-Version: 1.0\nMain-Class: ${prefix}\n".getBytes()
    manifestEntry.setSize(manifest.length)
    jos.putNextEntry(manifestEntry)
    jos.write(manifest)

    JarEntry main = new JarEntry("${prefix}.class")
    main.setSize(compiled.length)
    jos.putNextEntry(main)
    jos.write(compiled)

    def directories = ['META-INF/', 'META-INF/MANIFEST.MF']

    jars.each {file ->
        println "Merging ${file.name}"
        file.entries().each { JarEntry entry ->
            if (!directories.contains(entry.name)) {
                byte[] arr = file.getInputStream(entry).getBytes()
                jos.putNextEntry(entry)
                jos.write(arr)
                directories << entry.name
            }
        }
    }

    jos.close()
}

File file = new File(args[0])
String prefix = file.name.substring(0, file.name.indexOf('.'))
byte[] compiled = compile(prefix, file)
def jars = dependencies(args[0])
createJar(prefix, args[1], jars, compiled)
