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

void printUsageAndExit(int exitCode = 0, PrintStream out = System.out) {
    String usage =
// tag::usage1[]
            """
usage: octmpl2helm -h | template.yaml+

Provide one or more OpenShift Deployment template files.
The script will generate a Helm chart directory for each
of them based on the basename of the file.
"""
// end::usage[]

    out.println(usage.replaceAll(/\/\/.*\n/, ''))
    System.exit(exitCode)
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

    private mergeInValues(String dirName, String fileName) {
        File propertiesFile = new File(dirName, fileName)
        log.debug("Checking for properties file '${propertiesFile}'")
        Properties properties = new Properties()
        if (propertiesFile.exists()) {
            properties.load(new FileInputStream(propertiesFile))

            parameter.each { String key, Map templateParameter ->
                if (properties[templateParameter.name]) {
                    if (templateParameter.value) {
                        log.warn("Not overriding value '{}' with current content '{}' with new value '{}' from '{}'",
                                templateParameter.name, templateParameter.value,
                                properties[templateParameter.name], propertiesFile)
                    } else {
                        templateParameter.value = properties[templateParameter.name]
                    }
                }
            }
        }

    }

    public TemplateTransformer(String inputFilename) {
        log.info("Loading OC Template '{}'", inputFilename)
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
        template.parameters.each { templateParameter ->
            parameter[templateParameter.name] = templateParameter
        }
        String dirName = input.parent
        mergeInValues(dirName, "${chartName}.properties")
        mergeInValues(dirName, "${chartName}-common.properties")

        objects = template.objects
    }

    public void fixKinds() {
        objects.each { object ->
            if (object.kind == "DeploymentConfig") {
                log.info("Changing DeploymentConfig -> Deployment for '{}'", object.metadata.name)
                object.kind = "Deployment"
                object.apiVersion = "apps/v1"
                if (object?.spec?.strategy?.type == "Rolling") {
                    log.info("Changing Update strategy for '{}'", object.metadata.name)
                    object.spec.strategy.type = "RollingUpdate"
                    object.spec.strategy.rollingUpdate = [
                            maxSurge: object.spec.strategy.rollingParams.maxSurge,
                            maxUnavailable: object.spec.strategy.rollingParams.maxUnavailable,
                    ]
                    object.spec.strategy.remove("rollingParams")
                    object.spec.remove("triggers")
                }
            } else if (object.kind == "Route") {
                log.info("Changing apiVersion for '{}'", object.metadata.name)
                object.apiVersion = "route.openshift.io/v1"
        }
    }

    public void replaceParameters() {
        objects = _replaceParameters(objects)
    }

    private void createReplacementIfNecessary(String variableName) {
        if (!parameter[variableName].replacement) {
            String replacement = variableName.toLowerCase().replaceAll(/_([a-z0-9])/) { letters ->
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
        if (!object) {
            return
        }
        for (int i = 0; i < object.size(); i++) {
            object[i] = _replaceParameters(object[i])
        }
        return object
    }

    private printHeader(PrintWriter printWriter) {
        printWriter.println("""# This file is generated automatically - edit with care!
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

    private printValuesTemplateHeader(PrintWriter printWriter) {
        printWriter.println("""# This file is generated automatically - DO NOT EDIT - Use it as template for your value overrides in different environments
# Generation date ${new Date()}
# Cf. https://github.com/ascheman/oc-templates2helm.git for generator details
""")
    }

    private void dumpValues(File chartsDir) {
        File valuesFile = new File(chartsDir, "values.yaml")
        File valuesTemplateFile = new File(chartsDir, "values-template.yaml")
        log.info("Dumping values for '{}' to '{}'", chartName, valuesFile)

        valuesTemplateFile.withPrintWriter { PrintWriter valueTemplatePrintWriter ->
            printValuesTemplateHeader(valueTemplatePrintWriter)
            valuesFile.withPrintWriter { PrintWriter valuesPrintWriter ->
                printHeader(valuesPrintWriter)
                parameter.keySet().sort().each { String paramName ->
                    if (parameter[paramName].description) {
                        valuesPrintWriter.println("# ${parameter[paramName].description}")
                    }
                    if (parameter[paramName].replacement) {
                        valuesPrintWriter.println("${parameter[paramName].replacement}: ${parameter[paramName].value ?: '# TO_BE_REPLACED'}")
                        if (!parameter[paramName].value) {
                            valueTemplatePrintWriter.println("# ${parameter[paramName].description}")
                            valueTemplatePrintWriter.println("${parameter[paramName].replacement}: # Insert environment specific value here")
                        }
                    } else {
                        log.warn("Parameter '{}' was never used?", paramName)
                        valuesPrintWriter.println("# Variable '${paramName}' was never used")
                    }
                }
            }
        }
    }

    private void dumpTemplates(File chartsDir) {
        File templatesDir = new File(chartsDir, "templates")
        templatesDir.mkdirs()
        // Sometimes there is more than one object of a kind
        Map<String, List> kinds = [:]
        objects.each { object ->
            String kind = object.kind
            if (!kinds[kind]) {
                kinds[kind] = new ArrayList()
            }
            kinds[kind].add(object)
        }
        kinds.each { String kind, List objects ->
            if (kind) {
                File templateFile = new File(templatesDir, "${kind.toCharArray()}.yaml")
                log.info("Dumping #${objects.size()} objects of kind '{}' to file '{}'", kind, templateFile)
                templateFile.withPrintWriter { PrintWriter printWriter ->
                    printHeader(printWriter)
                    yaml.dump(objects[0], printWriter)
                    for (int objectNo = 1; objectNo < objects.size(); objectNo++) {
                        printWriter.println("---")
                        yaml.dump(objects[objectNo], printWriter)
                    }
                }
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
