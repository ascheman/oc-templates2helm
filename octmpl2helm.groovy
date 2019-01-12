#!/usr/bin/env groovy

/**
 * Generate Helm charts from OpenShift Deployment templates
 *
 * @author Gerd Aschemann <gerd@aschemann.net>
 */
@Grab("commons-io:commons-io:2.6")
@Grab("me.soliveirajr:menta-regex:0.9.5")
@Grab("org.slf4j:slf4j-simple:1.7.25")
@Grab("org.yaml:snakeyaml:1.23")

void printUsageAndExit (int exitCode = 0, PrintStream out = System.out) {
    String usage =
// tag::usage1[]
            """
usage: octmpl2helm -h | template.yaml+

Provide one or more OpenShift Deployment template files.
The script will generate a Helm chart directory for each
of them based on the basename of the file.
"""
// end::usage[]

    out.println (usage.replaceAll (/\/\/.*\n/, ''))
    System.exit (exitCode)
}

import groovy.util.logging.Slf4j
import org.apache.commons.io.FilenameUtils
import org.mentaregex.Regex
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

@Slf4j
public class TemplateTransformer {

    Yaml yaml

    Collection objects
    HashMap parameter = [:]
    String chartName

    public TemplateTransformer(String inputFilename) {
        log.info ("Loading OC Template '{}'", inputFilename)
        DumperOptions options = new DumperOptions()
        options.setPrettyFlow(true)
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK)
        options.setIndent(4)
        options.setSplitLines(false)
        options.setDefaultScalarStyle(DumperOptions.ScalarStyle.createStyle())
        yaml = new Yaml(options)

        File input = new File(inputFilename)
        chartName = FilenameUtils.getBaseName(inputFilename)
        def template = yaml.load(new FileInputStream(input))

        if (!template?.kind?.equals("Template")) {
            throw new IllegalArgumentException("Cannot transform kind '${template?.kind}' from input file '${inputFilename}")
        } else if (!template?.objects) {
            throw new IllegalArgumentException("Template file '${inputFilename}' does not contain any objects")
        }

        // Read parameters into hash for later replacement
        template.parameters.each {
            templateParameter ->
                parameter[templateParameter.name] = templateParameter
        }
        objects = template.objects
    }

    public void fixKinds() {
        objects.each {object ->
            if (object.kind == "DeploymentConfig") {
                log.info ("Changing DeploymentConfig -> Deployment for '{}'", object.metadata.name)
                object.kind = "Deployment"
                object.apiVersion = "apps/v1"
            }
        }
    }

    public void replaceParameters() {
        objects = _replaceParameters(objects)
    }

    private void createReplacementIfNecessary(String variableName) {
        if (!parameter[variableName].replacement) {
            String replacement = variableName.toLowerCase().replaceAll(/_([a-z0-9])/) {letters ->
                letters[1].toUpperCase()
            }
            log.info("${variableName} -> ${replacement}")
            parameter[variableName].replacement = replacement
        }
    }

    private def _replaceParameters(Integer object) {
        return object
    }

    private def _replaceParameters(Boolean object) {
        return object
    }

    private def _replaceParameters(String object) {
        String[] matches = Regex.match(object, "/\\\$(\\{*)([A-Z_]+)(\\}*)/g")
        if (matches) {
            if (matches.length % 3 != 0) {
                throw new RuntimeException("Line '${object}' does not match variable group")
            }
            log.debug "In Object '${object}':"
            matches.each { match ->
                log.debug "  Found '${match}'"
            }

            String original = object
            for (int currentGroup = 0; currentGroup < matches.length / 3; currentGroup++) {
                String variable = matches[currentGroup * 3 + 1]
                if (!parameter[variable]) {
                    // We should better throw new RuntimeException
                    log.warn("Parameter '${variable}' in '${original}' is not declared")
                    parameter[variable] = [name: variable]
                }
                createReplacementIfNecessary(variable)
                String replacement = parameter[variable].replacement
                object = object.replaceAll("\\\$\\{*${variable}\\}*", "{{ .Values.${replacement} }}".toString())
            }
        }
        return object
    }

    private def _replaceParameters(HashMap object) {
        object.each { key, value ->
            object[key] = _replaceParameters(value)
        }
        return object
    }

    private def _replaceParameters(Collection object) {
        for (int i = 0; i < object.size(); i++) {
            object[i] = _replaceParameters(object[i])
        }
        return object
    }

    private printHeader(PrintWriter printStream) {
        printStream.println("""# This file is generated automatically - edit with care!
# Generation date ${new Date()}
# Cf. https://github.com/ascheman/oc-templates2helm.git for generator details
""")
    }

    private void dumpChart(File chartsDir) {
        File chartFile = new File(chartsDir, "Chart.yaml")
        log.info("Dumping chart for '{}' to '{}'", chartName, chartFile)

        chartFile.withPrintWriter { PrintWriter printWriter ->
            printHeader(printWriter)
            printWriter.println("""apiVersion: v1
appVersion: "1.0"
description: A Helm chart for the ${chartName} application
name: ${chartName}
# The effective version will be computed during Helm generation
version: 0.0.1
# This is only added to make `helm lint` happy - configure your own icon!
icon: http://acme.org/replaceme.jpg 
""")

        }
    }

    private void dumpValues(File chartsDir) {
        File valuesFile = new File(chartsDir, "values.yaml")
        log.info("Dumping values for '{}' to '{}'", chartName, valuesFile)

        valuesFile.withPrintWriter { PrintWriter printWriter ->
            printHeader(printWriter)
            parameter.keySet().sort().each { String paramName ->
                if (parameter[paramName].description) {
                    printWriter.println("# ${parameter[paramName].description}")
                }
                if (parameter[paramName].replacement) {
                    printWriter.println("${parameter[paramName].replacement}: ${parameter[paramName].value ?: '# TO_BE_REPLACED'}")
                } else {
                    log.warn("Parameter '{}' was never used?", paramName)
                    printWriter.println("# Variable '${paramName}' was never used")
                }
            }
        }
    }

    private void dumpTemplates(File chartsDir) {
        File templatesDir = new File(chartsDir, "templates")
        templatesDir.mkdirs()
        objects.each { object ->
            String name = object.metadata.name
            if (name.contains ("{{")) {
                log.warn ("Object name '{}' contains variable using chart name instead", name)
                name = chartName
            }
            File templateFile = new File(templatesDir, "${object.kind}-${name}.yaml")
            log.info("Dumping object '{}' of kind '{}' to file '{}'", name, object.kind, templateFile)
            templateFile.withPrintWriter { PrintWriter printStream ->
                printHeader(printStream)
                yaml.dump(object, printStream)
            }
        }
    }

    public void dump(String targetDirname = "generated-helmcharts") {
        File targetDir = new File(targetDirname)
        File chartsDir = new File(targetDir, chartName)
        chartsDir.mkdirs()

        dumpChart(chartsDir)
        dumpValues(chartsDir)
        dumpTemplates(chartsDir)
    }
}

if (args.length < 1) {
    System.err.println("Missing argument")
    printUsageAndExit(1, System.err)
} else if (args[0].equals("-h")) {
    printUsageAndExit(0, System.out)
}

args.each { String arg ->
    TemplateTransformer ttf = new TemplateTransformer(arg)
    ttf.fixKinds()
    ttf.replaceParameters()
    ttf.dump()
}
