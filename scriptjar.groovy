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

import static org.codehaus.groovy.control.Phases.CLASS_GENERATION
import static org.codehaus.groovy.control.Phases.SEMANTIC_ANALYSIS

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

List<GroovyClass> compile(String prefix, File file) {
    CompilationUnit unit = new CompilationUnit()
    unit.addPhaseOperation(new RemoveStaticPrimaryClassNodeOperation(), SEMANTIC_ANALYSIS)
    unit.addSource(SourceUnit.create(prefix, file.text))
    unit.compile(CLASS_GENERATION)
    return unit.getClasses()
}

List<File> getGroovyLibs(List neededJars) {
    def libs = new File('.')
    if (System.getenv('GROOVY_HOME')) {
        libs = new File(System.getenv('GROOVY_HOME'), 'lib')
    }else if( new File( System.getProperty("user.home"), '.groovy/grapes' ).exists()){
        libs = new File(System.getenv('GROOVY_HOME'), 'lib')
    } else {
        println "Cann't find GROOVY_HOME"
        System.exit(1)
    }
    def groovylibs = libs.listFiles().findAll{jar ->
        neededJars.any{needed -> jar.name =~ needed  }
    }
    if (groovylibs) {
       return groovylibs
    } else {
        println "Cann't find Groovy lib in ${libs.absolutePath}, specify it manually as Grab dependency"
        System.exit(1)
    }
}

List dependencies(File source) {
    final GroovyClassLoader classLoader = new GroovyClassLoader()
    classLoader.parseClass(source.text)
    def files = Grape.resolve([:], Grape.listDependencies(classLoader)).collect{ new JarFile(it.path) }
    files.addAll(getGroovyLibs([/groovy-\d+.\d+.\d+.jar/]).collect{ new JarFile(it) })
    return files
}

void writeJarEntry(JarOutputStream jos, JarEntry entry, byte[] data) {
    entry.setSize(data.length)
    jos.putNextEntry(entry)
    jos.write(data)
}

byte[] createJar(String prefix, List jars, List<GroovyClass> compiled) {
    ByteArrayOutputStream output = new ByteArrayOutputStream()
    JarOutputStream jos = new JarOutputStream(output)

    jos.putNextEntry(new JarEntry('META-INF/'))
    writeJarEntry(jos, new JarEntry('META-INF/MANIFEST.MF'), "Manifest-Version: 1.0\nMain-Class: ${prefix}\n".getBytes())
    compiled.each { writeJarEntry(jos, new JarEntry("${it.name}.class"), it.bytes) }

    def directories = ['META-INF/', 'META-INF/MANIFEST.MF']

    jars.each {file ->
        println "Merging ${file.name}"
        file.entries().each { JarEntry entry ->
            if (!directories.contains(entry.name)) {
                writeJarEntry(jos, entry, file.getInputStream(entry).getBytes())
                directories << entry.name
            }
        }
    }

    jos.close()
    return output.toByteArray()
}

byte[] createUberjar(File file, String prefix) {
    List<GroovyClass> compiled = compile(prefix, file)
    def jars = dependencies(file)
    return createJar(prefix, jars, compiled)
}


if (args.size() < 2) {
    println "Usage: ./scriptjar.groovy input.groovy output.jar"
    System.exit(1)
}

File file = new File(args[0])
String prefix = file.name.substring(0, file.name.indexOf('.'))

new File(args[1]).withOutputStream {
    it << createUberjar(file, prefix)
}
