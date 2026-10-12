package org.openpnp.vision.pipeline.stages;

import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.Stage;
import org.openpnp.vision.pipeline.TerminalException;
import org.simpleframework.xml.Attribute;

import com.google.common.io.Files;

import bsh.engine.BshScriptEngineFactory;

@Stage(description="Run an arbitrary script file using the built in scripting engine. pipeline and stage are exposed as globals for use by the script. To return a pipeline result you can't use a return statement, but instead just let the object be the last thing the script evaluates.")
public class ScriptRun extends CvStage {
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
