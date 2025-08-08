package com.uni.cookoff.services;

import lombok.Getter;
import org.springframework.stereotype.Service;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

class CustomCodeExecutor implements Runnable {
    private final String sourceCode;
    private final String input;
    @Getter
    private String output = "";

    public CustomCodeExecutor(String sourceCode, String input) {
        this.sourceCode = sourceCode;
        this.input = input;
    }

    @Override
    public void run() {
        try {
            // Create a unique class name
            String className = "Solution" + UUID.randomUUID().toString().replace("-", "");

            // Modify source code to match the class name
            String modifiedCode = sourceCode.replaceFirst("class\\s+\\w+", "class " + className);

            // Create temporary file and compile
            Path tempDir = Files.createTempDirectory("java-execution");
            Path sourceFile = tempDir.resolve(className + ".java");
            Files.write(sourceFile, modifiedCode.getBytes());

            // Compile
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler.run(null, null, null, sourceFile.toString()) != 0) {
                output = "Compilation error";
                return;
            }

            // Capture stdout and provide stdin
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            System.setOut(new PrintStream(outputStream));
            System.setIn(new ByteArrayInputStream(input.getBytes()));

            // Execute the compiled code
            URLClassLoader classLoader = new URLClassLoader(
                    new URL[]{tempDir.toUri().toURL()}, getClass().getClassLoader());

            Class<?> cls = classLoader.loadClass(className);
            cls.getMethod("main", String[].class).invoke(null, (Object) new String[0]);

            output = outputStream.toString();
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            // Create a PrintWriter to write the stack trace to the StringWriter
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);

            // The full stack trace is now in the StringWriter
            String fullStackTrace = sw.toString();

            // Now your output will have all the details
            output = "Runtime error:\n" + fullStackTrace;
        } finally {
            // Restore standard streams
            System.setIn(System.in);
            System.setOut(System.out);
        }
    }

}