package org.openpnp.vision.pipeline.stages;

import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.Stage;
import org.openpnp.vision.pipeline.TerminalException;
import org.simpleframework.xml.Attribute;
import org.pmw.tinylog.Logger;

import com.google.common.io.Files;

import bsh.engine.BshScriptEngineFactory;

@Stage(description="Run an arbitrary script file using the built in scripting engine. pipeline and stage are exposed as globals for use by the script. To return a pipeline result you can't use a return statement, but instead just let the object be the last thing the script evaluates.")
public class ScriptRun extends CvStage {
    // Scope the cache strictly to CNN-dependent Jython .py stages.
    private static final String PYTHON_FACTORY = "org.python.jsr223.PyScriptEngineFactory";
    private static final String CACHED_RESULT_KEY = "__openpnp_result";
    private static final String CACHED_CODE_KEY = "__openpnp_code";
    // Vision callers may clone CvPipeline for each operation. Cache by absolute script path
    // so those new stage instances reuse the same compiled interpreter. Limit memory and
    // serialize Jython, which is not thread-safe when sharing one engine.
    private static final Object CNN_JYTHON_LOCK = new Object();
    private static final int CNN_CACHE_LIMIT = 8;
    private static final Map<String, CachedCnnJython> CNN_JYTHON_CACHE =
            new LinkedHashMap<>(16, 0.75f, true);

    @Attribute
    private File file = new File("");

    public File getFile() {
        return file;
    }

    public void setFile(File file) {
        this.file = file;
    }

    @Attribute
    private String args = new String("");

    public String getArgs() {
        return args;
    }

    public void setArgs(String args) {
        this.args = args;
    }

    @Override
    public Result process(CvPipeline pipeline) throws Exception {
        boolean cnnDependent = pipeline.getStages().stream().anyMatch(candidate ->
                candidate instanceof CNN && candidate.isEnabled() && pipeline.getResult(candidate) != null);
        try {
            return processScript(pipeline, cnnDependent);
        }
        catch (Exception e) {
            if (cnnDependent) {
                throw new TerminalException(e);
            }
            throw e;
        }
    }

    private Result processScript(CvPipeline pipeline, boolean cnnDependent) throws Exception {
        if (!file.exists()) {
            if (cnnDependent) {
                throw new Exception("CNN interpretation script does not exist: " + file);
            }
            return null;
        }

        // Keep all non-CNN engines and .java/.bsh/.js stages on the legacy path.
        if (cnnDependent && "py".equalsIgnoreCase(Files.getFileExtension(file.getName()))) {
            return evaluateCachedCnnJython(pipeline);
        }

        ScriptEngineManager manager = new ScriptEngineManager();
        // Hack to fix BSH on Windows. See https://github.com/openpnp/openpnp/issues/462
        manager.registerEngineExtension("bsh", new BshScriptEngineFactory());
        manager.registerEngineExtension("java", new BshScriptEngineFactory());
        ScriptEngine engine = manager.getEngineByExtension(Files.getFileExtension(file.getName()));
        
        if (engine == null) {
            throw new Exception("Unable to find scriping engine for " + file);
        }

        engine.put("pipeline", pipeline);
        engine.put("stage", this);
        engine.put("args",args);

        if ("org.python.jsr223.PyScriptEngineFactory".equals(engine.getFactory().getClass().getName())) {
            return evaluateJython(engine, cnnDependent);
        }

        try (FileReader reader = new FileReader(file)) {
            Object result = engine.eval(reader);
            return checkedResult(result, cnnDependent);
        }
    }

    /**
     * One interpreter and precompiled Python code per CNN script file, even when the vision
     * operation clones its pipeline. Each invocation gets fresh bindings. A bounded global
     * lock serializes Jython use but has no effect on non-CNN scripts or event handlers.
     */
    private Result evaluateCachedCnnJython(CvPipeline pipeline) throws Exception {
        // Compare contents, not just timestamps: edits must take effect immediately, even
        // if the filesystem timestamp granularity or file size would hide them.
        final String filename = file.toPath().toAbsolutePath().normalize().toString();
        final String source = Files.toString(file, StandardCharsets.UTF_8);
        synchronized (CNN_JYTHON_LOCK) {
            CachedCnnJython cached = CNN_JYTHON_CACHE.get(filename);
            if (cached == null || !source.equals(cached.source)) {
                if (cached != null) {
                    CNN_JYTHON_CACHE.remove(filename);
                    closeCnnJythonEngine(cached.engine);
                }
                cached = compileCnnJython(filename, source);
                CNN_JYTHON_CACHE.put(filename, cached);
                while (CNN_JYTHON_CACHE.size() > CNN_CACHE_LIMIT) {
                    Iterator<CachedCnnJython> oldEntries = CNN_JYTHON_CACHE.values().iterator();
                    CachedCnnJython evicted = oldEntries.next();
                    oldEntries.remove();
                    closeCnnJythonEngine(evicted.engine);
                }
                Logger.debug("CNN ScriptRun \"{}\" compiled Jython script \"{}\"", getName(), filename);
            }
            Bindings bindings = cached.engine.createBindings();
            bindings.put("pipeline", pipeline);
            bindings.put("stage", this);
            bindings.put("args", args);
            bindings.put(ScriptEngine.FILENAME, filename);
            bindings.put("__file__", filename);
            bindings.put(CACHED_CODE_KEY, cached.pythonCode);
            bindings.put(CACHED_RESULT_KEY, null);
            long started = System.nanoTime();
            cached.runner.eval(bindings);
            Result result = checkedResult(bindings.get(CACHED_RESULT_KEY), true);
            Logger.debug("CNN ScriptRun \"{}\" cached Jython execution took {} ms.",
                    getName(), (System.nanoTime() - started) / 1_000_000.0);
            return result;
        }
    }

    private static CachedCnnJython compileCnnJython(String filename, String source) throws Exception {
        ScriptEngine engine = new ScriptEngineManager().getEngineByExtension("py");
        if (engine == null || !PYTHON_FACTORY.equals(engine.getFactory().getClass().getName())
                || !(engine instanceof Compilable)) {
            throw new Exception("CNN cached ScriptRun requires a compilable Jython .py engine.");
        }
        try {
            engine.put("__openpnp_source", source);
            engine.put("__openpnp_filename", filename);
            // Same final-expression / final-assignment capture contract as the legacy path,
            // but compile the AST once instead of parsing 400+ lines on every frame.
            String bootstrap = "import ast as __openpnp_ast\n"
                    + "__openpnp_tree = __openpnp_ast.parse(__openpnp_source, __openpnp_filename, 'exec')\n"
                    + "if __openpnp_tree.body:\n"
                    + "    __openpnp_last = __openpnp_tree.body[-1]\n"
                    + "    if isinstance(__openpnp_last, __openpnp_ast.Expr):\n"
                    + "        __openpnp_tree.body[-1] = __openpnp_ast.copy_location("
                    + "__openpnp_ast.Assign(targets=[__openpnp_ast.Name(id='"
                    + CACHED_RESULT_KEY + "', ctx=__openpnp_ast.Store())], "
                    + "value=__openpnp_last.value), __openpnp_last)\n"
                    + "    elif isinstance(__openpnp_last, __openpnp_ast.Assign):\n"
                    + "        __openpnp_last.targets.append(__openpnp_ast.Name(id='"
                    + CACHED_RESULT_KEY + "', ctx=__openpnp_ast.Store()))\n"
                    + CACHED_CODE_KEY + " = compile(__openpnp_ast.fix_missing_locations("
                    + "__openpnp_tree), __openpnp_filename, 'exec')\n";
            engine.eval(bootstrap);
            Object pythonCode = engine.get(CACHED_CODE_KEY);
            if (pythonCode == null) {
                throw new Exception("Jython failed to compile CNN interpreter script " + filename);
            }
            CompiledScript runner = ((Compilable) engine).compile(
                    "exec " + CACHED_CODE_KEY + " in globals()\n");
            return new CachedCnnJython(filename, source, engine, runner, pythonCode);
        }
        catch (Exception | LinkageError e) {
            closeCnnJythonEngine(engine);
            throw e;
        }
    }

    private static void closeCnnJythonEngine(ScriptEngine engine) {
        if (engine instanceof AutoCloseable) {
            try {
                ((AutoCloseable) engine).close();
            }
            catch (Exception e) {
                Logger.warn(e, "Unable to close cached CNN Jython interpreter.");
            }
        }
    }

    private static final class CachedCnnJython {
        private final String filename;
        private final String source;
        private final ScriptEngine engine;
        private final CompiledScript runner;
        private final Object pythonCode;

        private CachedCnnJython(String filename, String source, ScriptEngine engine,
                CompiledScript runner, Object pythonCode) {
            this.filename = filename;
            this.source = source;
            this.engine = engine;
            this.runner = runner;
            this.pythonCode = pythonCode;
        }
    }

    private Result evaluateJython(ScriptEngine engine, boolean cnnDependent) throws Exception {
        // Jython's file-mode eval returns None, including for a final expression. Capture that
        // value in the same interpreter without re-evaluating user code or changing its file.
        String resultName = "__openpnp_result_" + UUID.randomUUID().toString().replace("-", "");
        String sourceName = resultName + "_source";
        String filenameName = resultName + "_filename";
        String treeName = resultName + "_tree";
        String astName = resultName + "_ast";
        String lastName = resultName + "_last";
        engine.put(sourceName, Files.toString(file, StandardCharsets.UTF_8));
        engine.put(filenameName, file.getAbsolutePath());
        String bootstrap = "import ast as " + astName + "\n"
                + treeName + " = " + astName + ".parse(" + sourceName + ", " + filenameName + ", 'exec')\n"
                + resultName + " = None\n"
                + "if " + treeName + ".body:\n"
                + "    " + lastName + " = " + treeName + ".body[-1]\n"
                + "    if isinstance(" + lastName + ", " + astName + ".Expr):\n"
                + "        " + treeName + ".body[-1] = " + astName + ".copy_location("
                + astName + ".Assign(targets=[" + astName + ".Name(id='" + resultName
                + "', ctx=" + astName + ".Store())], value=" + lastName + ".value), " + lastName + ")\n"
                + "    elif isinstance(" + lastName + ", " + astName + ".Assign):\n"
                + "        " + lastName + ".targets.append(" + astName + ".Name(id='" + resultName
                + "', ctx=" + astName + ".Store()))\n"
                + "exec compile(" + astName + ".fix_missing_locations(" + treeName + "), "
                + filenameName + ", 'exec') in globals()\n";
        engine.eval(bootstrap);
        return checkedResult(engine.get(resultName), cnnDependent);
    }

    private Result checkedResult(Object result, boolean cnnDependent) throws Exception {
        if (result instanceof Result) {
            return (Result) result;
        }
        if (cnnDependent) {
            throw new Exception("CNN interpretation must return a CvStage.Result as its final "
                    + "expression or assignment.");
        }
        return null;
    }
}
