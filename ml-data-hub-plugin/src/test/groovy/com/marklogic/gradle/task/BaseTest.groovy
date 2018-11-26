/*
 * Copyright 2012-2018 MarkLogic Corporation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.marklogic.gradle.task

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.marklogic.client.FailedRequestException
import com.marklogic.client.document.DocumentManager
import com.marklogic.client.eval.EvalResult
import com.marklogic.client.eval.EvalResultIterator
import com.marklogic.client.eval.ServerEvaluationCall
import com.marklogic.client.io.DocumentMetadataHandle
import com.marklogic.client.io.Format
import com.marklogic.client.io.InputStreamHandle
import com.marklogic.client.io.StringHandle
import com.marklogic.hub.DatabaseKind
import com.marklogic.hub.HubConfig
import com.marklogic.hub.HubConfigBuilder
import com.marklogic.mgmt.ManageClient
import com.marklogic.mgmt.resource.databases.DatabaseManager
import com.marklogic.rest.util.Fragment
import com.marklogic.rest.util.JsonNodeUtil
import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.IOUtils
import org.custommonkey.xmlunit.XMLUnit
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Document
import org.xml.sax.SAXException
import spock.lang.Specification

import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

class BaseTest extends Specification {

    // this value is for legacy purposes.  on dev should always be 5
    static final int MOD_COUNT_WITH_TRACE_MODULES = 26
    static final int MOD_COUNT = 5
    // this value under good security conditions is 2 because hub-admin-user cannot read options files directly.
    static final int MOD_COUNT_NO_OPTIONS_NO_TRACES = 109
    static final TemporaryFolder testProjectDir = new TemporaryFolder()
    static File buildFile
    static File propertiesFile

    static ManageClient _manageClient;
    static DatabaseManager _databaseManager;

    static HubConfig _hubConfig = null

    static BuildResult runTask(String... task) {
        return GradleRunner.create()
                .withProjectDir(testProjectDir.root)
                .withArguments(task)
                .withDebug(true)
                .withPluginClasspath()
                .build()
    }

    BuildResult runFailTask(String... task) {
        return GradleRunner.create()
                .withProjectDir(testProjectDir.root)
                .withArguments(task)
                .withDebug(true)
                .withPluginClasspath().buildAndFail()
    }

    static HubConfig hubConfig() {
        if (_hubConfig == null || !_hubConfig.projectDir.equals(testProjectDir.root.toString())) {
            _hubConfig = HubConfigBuilder.newHubConfigBuilder(testProjectDir.root.toString())
                    .withPropertiesFromEnvironment()
                    .build()
        }
        return _hubConfig
    }

    void installStagingDoc(String uri, DocumentMetadataHandle meta, String doc) {
        hubConfig().newStagingClient().newDocumentManager().write(uri, meta, new StringHandle(doc))
    }


    void installFinalDoc(String uri, DocumentMetadataHandle meta, String doc) {
        hubConfig().newFinalClient().newDocumentManager().write(uri, meta, new StringHandle(doc))
    }

    static void installModule(String path, String localPath) {

        InputStreamHandle handle = new InputStreamHandle(new File("src/test/resources/" + localPath).newInputStream())
        String ext = FilenameUtils.getExtension(path)
        switch (ext) {
            case "xml":
                handle.setFormat(Format.XML)
                break
            case "json":
                handle.setFormat(Format.JSON)
                break
            default:
                handle.setFormat(Format.TEXT)
        }

        DocumentManager modMgr = hubConfig().newModulesDbClient().newDocumentManager()
        modMgr.write(path, handle);
    }


    void clearDatabases(String... databases) {
        ServerEvaluationCall eval = hubConfig().newStagingClient().newServerEval();
        String installer = '''
            declare variable $databases external;
            for $database in fn:tokenize($databases, ",")
             return
               xdmp:eval(
                 'cts:uris() ! xdmp:document-delete(.)',
                 (),
                 map:entry("database", xdmp:database($database))
               )
        '''
        eval.addVariable("databases", String.join(",", databases));
        EvalResultIterator result = eval.xquery(installer).eval();
    }

    protected Document getXmlFromResource(String resourceName) throws IOException, ParserConfigurationException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance()
        factory.setIgnoringElementContentWhitespace(true)
        factory.setNamespaceAware(true)
        DocumentBuilder builder = factory.newDocumentBuilder()
        return builder.parse(new File("src/test/resources/" + resourceName).getAbsoluteFile())
    }

    protected JsonNode getJsonResource(String absoluteFilePath) {
        try {
            InputStream jsonDataStream = new FileInputStream(new File(absoluteFilePath))
            ObjectMapper jsonDataMapper = new ObjectMapper()
            return jsonDataMapper.readTree(jsonDataStream)
        } catch (IOException e) {
            e.printStackTrace()
        }
    }

    static void copyResourceToFile(String resourceName, File dest) {
        def file = new File("src/test/resources/" + resourceName)
        FileUtils.copyFile(file, dest)
    }
    
    static void writeSSLFiles(File serverFile, File ssl) {
        def files = []
        files << ssl
        files << serverFile
        ObjectNode serverFiles = JsonNodeUtil.mergeJsonFiles(files);
        FileUtils.writeStringToFile(serverFile, serverFiles.toString());        
    }

    static int getStagingDocCount() {
        return getStagingDocCount(null)
    }

    static int getStagingDocCount(String collection) {
        return getDocCount(HubConfig.DEFAULT_STAGING_NAME, collection)
    }

    static int getFinalDocCount() {
        return getFinalDocCount(null)
    }

    static int getFinalDocCount(String collection) {
        return getDocCount(HubConfig.DEFAULT_FINAL_NAME, collection)
    }

    static int getModulesDocCount() {
        return getDocCount(HubConfig.DEFAULT_MODULES_DB_NAME, null)
    }

    static int getDocCount(String database, String collection) {
        int count = 0
        String collectionName = ""
        if (collection != null) {
            collectionName = "'" + collection + "'"
        }
        EvalResultIterator resultItr = runInDatabase("xdmp:estimate(fn:collection(" + collectionName + "))", database)
        if (resultItr == null || !resultItr.hasNext()) {
            return count
        }
        EvalResult res = resultItr.next()
        count = Math.toIntExact((long) res.getNumber())
        return count
    }

    static EvalResultIterator runInDatabase(String query, String databaseName) {
        ServerEvaluationCall eval
        switch (databaseName) {
            case HubConfig.DEFAULT_STAGING_NAME:
                eval = hubConfig().newStagingClient().newServerEval()
                break
            case HubConfig.DEFAULT_FINAL_NAME:
                eval = hubConfig().newFinalClient().newServerEval()
                break
            case HubConfig.DEFAULT_MODULES_DB_NAME:
                eval = hubConfig().newModulesDbClient().newServerEval()
                break
            case HubConfig.DEFAULT_JOB_NAME:
                eval = hubConfig().newJobDbClient().newServerEval()
        }
        try {
            return eval.xquery(query).eval()
        }
        catch (FailedRequestException e) {
            e.printStackTrace()
            throw e
        }
    }

    static void createBuildFile() {
        buildFile = testProjectDir.newFile('build.gradle')
        buildFile << """
            plugins {
                id 'com.marklogic.ml-data-hub'
            }
        """
    }

    static void createFullPropertiesFile() {
        def props = Paths.get(".").resolve("gradle.properties")
        propertiesFile = testProjectDir.newFile("gradle.properties")
        def dst = propertiesFile.toPath()
        Files.copy(props, dst, StandardCopyOption.REPLACE_EXISTING)
    }

    static void createGradleFiles() {
        createBuildFile()
        createFullPropertiesFile()
    }

    static DatabaseManager getDatabaseManager() {
        if (_databaseManager == null) {
            _databaseManager = new DatabaseManager(getManageClient());
        }
        return _databaseManager;
    }

    static ManageClient getManageClient() {
        if (_manageClient == null) {
            _manageClient = hubConfig().getManageClient();
        }
        return _manageClient;
    }

    static int getStagingRangePathIndexSize() {
        Fragment databseFragment = getDatabaseManager().getPropertiesAsXml(_hubConfig.getDbName(DatabaseKind.STAGING));
        return databseFragment.getElementValues("//m:range-path-index").size()
    }

    static int getFinalRangePathIndexSize() {
        Fragment databseFragment = getDatabaseManager().getPropertiesAsXml(_hubConfig.getDbName(DatabaseKind.FINAL));
        return databseFragment.getElementValues("//m:range-path-index").size()
    }

    static int getJobsRangePathIndexSize() {
        Fragment databseFragment = getDatabaseManager().getPropertiesAsXml(_hubConfig.getDbName(DatabaseKind.JOB));
        return databseFragment.getElementValues("//m:range-path-index").size()
    }

    def setupSpec() {
        XMLUnit.setIgnoreWhitespace(true)
        testProjectDir.create()
    }
}
