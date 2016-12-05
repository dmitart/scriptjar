#!/usr/bin/env groovy

import groovy.grape.Grape
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.tools.GroovyClass

import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

import groovy.io.FileType

import static org.codehaus.groovy.control.Phases.CLASS_GENERATION

List<GroovyClass> compile(String prefix, File file) {
    List<GroovyClass> classes = [] as List<GroovyClass>

    // disable groovy grapes - we're resolving these ahead of time
    CompilerConfiguration compilerConfig = new CompilerConfiguration()
    Set disabledTransforms = ['groovy.grape.GrabAnnotationTransformation'] as Set
    compilerConfig.setDisabledGlobalASTTransformations(disabledTransforms)

    // compile main class
    CompilationUnit unit = new CompilationUnit(compilerConfig)
    unit.addSource(SourceUnit.create(prefix, file.text))
    println "${file.name} => ${prefix} (main class)"
    unit.compile(CLASS_GENERATION)
    classes += unit.getClasses()

    // compile groovy files in same folder
    getSiblingGroovyFiles(file).each {
        CompilationUnit dependentUnit = new CompilationUnit(compilerConfig)
        def className = it.name.replaceAll(/\.groovy$/, '')
        println "${it.name} => ${className} (lib)"
        dependentUnit.addSource(SourceUnit.create(className, it.text))
        dependentUnit.compile(CLASS_GENERATION)

        classes += dependentUnit.getClasses()
    }

    return classes
}

List<File> getSiblingGroovyFiles(File mainGroovyFile) {
    def groovyFileRe = /.*\.groovy$/
    List<File> files = [] as List<File>
    mainGroovyFile.getAbsoluteFile().getParentFile().eachFile(FileType.FILES) {
        if (!it.hidden && it.name =~ groovyFileRe && it.name != mainGroovyFile.name && it.name != 'scriptJar.groovy') {
            files << it
        }
    }

    return files
}

List<File> getGroovyLibs(List neededJars) {
    def libs = new File('.')
    if (System.getenv('GROOVY_HOME')) {
        libs = new File(System.getenv('GROOVY_HOME'), 'lib')
    }else if( System.getProperty("user.home") &&
              new File( System.getProperty("user.home"), '.groovy/grapes' ).exists() ) {
        libs = new File( System.getProperty("user.home"), '.groovy/grapes' )
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
        println "Can't find Groovy lib in ${libs.absolutePath}, specify it manually as Grab dependency"
        System.exit(1)
    }
}

List dependencies(File source) {
    final GroovyClassLoader classLoader = new GroovyClassLoader()
    classLoader.parseClass(source.text)
    getSiblingGroovyFiles(source).each {
      classLoader.parseClass(it.text)
    }
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
    compiled.each {
      writeJarEntry(jos, new JarEntry("${it.name}.class"), it.bytes)
    }

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
