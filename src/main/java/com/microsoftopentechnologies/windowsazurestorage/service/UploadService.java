/*
 Copyright 2017 Microsoft Open Technologies, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package com.microsoftopentechnologies.windowsazurestorage.service;

import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.SharedAccessBlobPermissions;
import com.microsoft.azure.storage.file.CloudFile;
import com.microsoft.azure.storage.file.FileRequestOptions;
import com.microsoft.azure.storage.file.SharedAccessFilePermissions;
import com.microsoft.jenkins.azurecommons.telemetry.AppInsightsConstants;
import com.microsoft.jenkins.azurecommons.telemetry.AppInsightsUtils;
import com.microsoftopentechnologies.windowsazurestorage.AzureBlob;
import com.microsoftopentechnologies.windowsazurestorage.AzureBlobMetadataPair;
import com.microsoftopentechnologies.windowsazurestorage.AzureStoragePlugin;
import com.microsoftopentechnologies.windowsazurestorage.Messages;
import com.microsoftopentechnologies.windowsazurestorage.beans.StorageAccountInfo;
import com.microsoftopentechnologies.windowsazurestorage.exceptions.WAStorageException;
import com.microsoftopentechnologies.windowsazurestorage.helper.AzureUtils;
import com.microsoftopentechnologies.windowsazurestorage.helper.Constants;
import com.microsoftopentechnologies.windowsazurestorage.helper.HttpUtils;
import com.microsoftopentechnologies.windowsazurestorage.helper.Utils;
import com.microsoftopentechnologies.windowsazurestorage.service.model.UploadServiceData;
import com.microsoftopentechnologies.windowsazurestorage.service.model.UploadType;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Util;
import hudson.remoting.VirtualChannel;
import jenkins.MasterToSlaveFileCallable;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.FileRequestEntity;
import org.apache.commons.httpclient.methods.PutMethod;
import org.apache.commons.httpclient.methods.RequestEntity;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;

import javax.xml.bind.DatatypeConverter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.StringTokenizer;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class UploadService extends StoragePluginService<UploadServiceData> {
    protected static final String ZIP_FOLDER_NAME = "artifactsArchive";
    protected static final String ZIP_NAME = "archive.zip";
    protected static final String UPLOAD = "Upload";
    protected static final String UPLOAD_FAILED = "UploadFailed";
    private static final int UPLOAD_THREAD_COUNT = 16;
    private static final int KEEP_ALIVE_TIME = 1;
    private static final int TIME_OUT = 1;
    private static final TimeUnit TIME_OUT_UNIT = TimeUnit.DAYS;

    private AtomicInteger filesUploaded = new AtomicInteger(0);
    private ExecutorService executorService = new ThreadPoolExecutor(UPLOAD_THREAD_COUNT, UPLOAD_THREAD_COUNT,
            KEEP_ALIVE_TIME, TimeUnit.SECONDS, new LinkedBlockingDeque<Runnable>());

    protected UploadService(UploadServiceData serviceData) {
        super(serviceData);
    }

    protected static class UploadObject {
        private String name;
        private FilePath src;
        private String url;
        private String sas;
        private String storageType;

        public UploadObject(String name, FilePath src, String url, String sas, String storageType) {
            this.name = name;
            this.src = src;
            this.url = url;
            this.sas = sas;
            this.storageType = storageType;
        }

        public String getName() {
            return name;
        }

        public String getUrl() {
            return url;
        }

        public String getSas() {
            return sas;
        }

        public String getStorageType() {
            return storageType;
        }

        public FilePath getSrc() {
            return src;
        }
    }

    protected static class UploadResult {
        private int statusCode;
        private String responseBody;
        private String fileHash;
        private String name;
        private String url;
        private long byteSize;
        private String storageType;
        private long startTime;
        private long endTime;

        public UploadResult(int statusCode, String responseBody, String fileHash, String name,
                            String url, long byteSize, String storageType, long startTime, long endTime) {
            this.statusCode = statusCode;
            this.responseBody = responseBody;
            this.fileHash = fileHash;
            this.name = name;
            this.url = url;
            this.byteSize = byteSize;
            this.storageType = storageType;
            this.startTime = startTime;
            this.endTime = endTime;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getResponseBody() {
            return responseBody;
        }

        public String getFileHash() {
            return fileHash;
        }

        public String getName() {
            return name;
        }

        public String getUrl() {
            return url;
        }

        public long getByteSize() {
            return byteSize;
        }

        public String getStorageType() {
            return storageType;
        }

        public long getStartTime() {
            return startTime;
        }

        public long getEndTime() {
            return endTime;
        }
    }

    final class UploadOnSlave extends MasterToSlaveFileCallable<List<Future<UploadResult>>> {
        private List<UploadObject> uploadObjects;

        UploadOnSlave(List<UploadObject> uploadObjects) {
            this.uploadObjects = uploadObjects;
        }


        @Override
        public List<Future<UploadResult>> invoke(File f, VirtualChannel channel)
                throws IOException, InterruptedException {
            List<Future<UploadResult>> results = new ArrayList<>();
            for (UploadObject uploadObject : uploadObjects) {
                Future<UploadResult> result = getExecutorService().submit(new UploadThread(uploadObject));
                results.add(result);
            }
            return results;
        }
    }

    protected void updateAzureBlobs(List<Future<UploadResult>> results, List<AzureBlob> azureBlobs) {

        try {
            for (Future<UploadResult> result : results) {
                UploadResult uploadResult = result.get();
                if (uploadResult.getStatusCode() == HttpStatus.SC_CREATED) {
                    AzureBlob azureBlob = new AzureBlob(
                            uploadResult.getName(),
                            uploadResult.getUrl(),
                            uploadResult.getFileHash(),
                            uploadResult.getByteSize(),
                            uploadResult.getStorageType());

                    filesUploaded.addAndGet(1);
                    azureBlobs.add(azureBlob);

                   println("Uploaded to file storage with uri " + uploadResult.getUrl() + " in " + getTime(uploadResult.getEndTime() - uploadResult.getStartTime()));
                } else {
                    // TODO log
                    println("Failed to upload, error code: " + HttpStatus.getStatusText(uploadResult.getStatusCode())
                            + " details: " + uploadResult.getResponseBody());
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            final String message = Messages.AzureStorageBuilder_download_err(
                    getServiceData().getStorageAccountInfo().getStorageAccName()) + ":" + e.getMessage();
            e.printStackTrace(error(message));
            println(message);
            setRunUnstable();
        }
    }

    private String getFileHash(FilePath src) throws WAStorageException {

        try {
            final MessageDigest md = DigestUtils.getMd5Digest();

            try (InputStream inputStream = src.read();
                 DigestInputStream digestInputStream = new DigestInputStream(inputStream, md)) {
                String s = DigestUtils.md5Hex(inputStream);

            }

            return DatatypeConverter.printHexBinary(md.digest());
        } catch (IOException | InterruptedException e) {
            throw new WAStorageException(e.getMessage(), e);
        }
    }

    protected String generateWriteSASURL(StorageAccountInfo storageAccountInfo, String fileName,
                                         String storageType, String name) throws Exception {
        if (storageType.equalsIgnoreCase(Constants.BLOB_STORAGE)) {
            return AzureUtils.generateBlobSASURL(storageAccountInfo, name, fileName,
                    EnumSet.of(SharedAccessBlobPermissions.WRITE));
        } else if (storageType.equalsIgnoreCase(Constants.FILE_STORAGE)) {
            return AzureUtils.generateFileSASURL(storageAccountInfo, name, fileName,
                    EnumSet.of(SharedAccessFilePermissions.WRITE,
                            SharedAccessFilePermissions.CREATE));
        }
        throw new Exception("Unknown storage type. Please re-configure your job and build again.");
    }

    static class UploadThread implements Callable<UploadResult> {
        private UploadObject uploadObject;

        UploadThread(UploadObject uploadObject) {
            this.uploadObject = uploadObject;
        }

        /**
         * @return
         * @throws Exception
         */
        @Override
        public UploadResult call() throws Exception {
            FilePath src = uploadObject.getSrc();
            String storageType = uploadObject.getStorageType();
            String url = uploadObject.getUrl();
            String sas = uploadObject.getSas();
            RequestEntity requestEntity = new FileRequestEntity(new File(src.getRemote()), null);

            long startTime = System.currentTimeMillis();
            ImmutablePair<Integer, String> response = null;
            if (Constants.BLOB_STORAGE.equals(storageType)) {
                PutMethod method = generateBlobWriteMethod(url, sas);
                method.setRequestEntity(requestEntity);
                response = execute(method);
            } else if (Constants.FILE_STORAGE.equals(storageType)) {
                PutMethod createMethod = generateFileCreateMethod(url, sas, requestEntity.getContentLength());
                ImmutablePair<Integer, String> createResponse = execute(createMethod);
                if (createResponse.left == HttpStatus.SC_CREATED) {
                    PutMethod writeMethod = generateFileWriteMethod(url, sas, requestEntity.getContentLength());
                    writeMethod.setRequestEntity(requestEntity);
                    response = execute(writeMethod);
                } else {
                    // failed to create file
                    System.out.println("");
                }
            } else {
                // unknown storage type
                System.out.println("");
            }
            long endTime = System.currentTimeMillis();

            // send AI event.
            AzureStoragePlugin.sendEvent(AppInsightsConstants.AZURE_FILE_STORAGE, UPLOAD,
                    "StorageAccount", hashedStorageAcc,
                    "ContentLength", String.valueOf(localPath.length()));


            String md;
            try (InputStream is = src.read()) {
                md = DigestUtils.md5Hex(is);
            }
            return new UploadResult(response.left, response.right, md, uploadObject.getName(),
                    uploadObject.getUrl(), src.length(), uploadObject.getStorageType(),
                    startTime, endTime);
        }

        private ImmutablePair<Integer, String> execute(PutMethod method) {
            HttpClient client = HttpUtils.getClient();
            int code = 0;
            String responseBody = "";
            try {
                code = client.executeMethod(method);
                responseBody = method.getResponseBodyAsString();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                method.releaseConnection();
            }
            return new ImmutablePair<>(code, responseBody);
        }

        private PutMethod generateBlobWriteMethod(String url, String sas) {
            String sasUrl = url + "?" + sas;
            PutMethod method = new PutMethod(sasUrl);
            method.addRequestHeader("x-ms-blob-type", "BlockBlob");
            return method;
        }

        private PutMethod generateFileCreateMethod(String url, String sas, long contentLength) {
            String sasUrl = url + "?" + sas;
            PutMethod method = new PutMethod(sasUrl);
            method.addRequestHeader("x-ms-type", "file");
            method.addRequestHeader("x-ms-content-length", String.valueOf(contentLength));
            method.addRequestHeader("Content-Length", "0");
            return method;
        }

        private PutMethod generateFileWriteMethod(String url, String sas, long contentLength) {
            String sasUrl = url + "?comp=range&" + sas;
            PutMethod method = new PutMethod(sasUrl);
            method.addRequestHeader("x-ms-type", "file");
            method.addRequestHeader("Content-Length", String.valueOf(contentLength + 1));

            method.addRequestHeader("x-ms-range", String.format("bytes=0-%d", contentLength));
            method.addRequestHeader("x-ms-write", "Update");
            return method;
        }
    }

    protected abstract void uploadIndividuals(String embeddedVP, FilePath[] paths,
                                              FilePath workspace) throws WAStorageException;

    protected abstract void uploadArchive(String archiveIncludes) throws WAStorageException;

    @Override
    public final int execute() throws WAStorageException {
        final UploadServiceData serviceData = getServiceData();

        if (serviceData.getUploadType() == UploadType.INVALID) {
            // no files are uploaded
            println("Upload type is INVALID, nothing to do.");
            return 0;
        }

        println(Messages.WAStoragePublisher_container_name(serviceData.getContainerName()));
        println(Messages.WAStoragePublisher_share_name(serviceData.getFileShareName()));
        println(Messages.WAStoragePublisher_filepath(serviceData.getFilePath()));
        println(Messages.WAStoragePublisher_virtualpath(serviceData.getVirtualPath()));
        println(Messages.WAStoragePublisher_excludepath(serviceData.getExcludedFilesPath()));
        int filesNeedUpload = 0; // Counter to track no. of files that are need uploaded
        int filesCount = 0;
        try {
            final FilePath workspacePath = serviceData.getRemoteWorkspace();
            println(Messages.WAStoragePublisher_uploading());

            final StringBuilder archiveIncludes = new StringBuilder();

            StringTokenizer strTokens = new StringTokenizer(serviceData.getFilePath(), FP_SEPARATOR);
            while (strTokens.hasMoreElements()) {
                String fileName = strTokens.nextToken();
                String embeddedVP = null;

                if (fileName != null && fileName.contains("::")) {
                    int embVPSepIndex = fileName.indexOf("::");

                    // Separate fileName and Virtual directory name.
                    if (fileName.length() > embVPSepIndex + 1) {
                        embeddedVP = fileName.substring(embVPSepIndex + 2, fileName.length());

                        if (StringUtils.isBlank(embeddedVP)) {
                            embeddedVP = null;
                        } else if (!embeddedVP.endsWith(Constants.FWD_SLASH)) {
                            embeddedVP = embeddedVP + Constants.FWD_SLASH;
                        }
                    }
                    fileName = fileName.substring(0, embVPSepIndex);
                }

                // List all the paths without the zip archives.
                FilePath[] paths = workspacePath.list(fileName, excludedFilesAndZip());
                FilePath[] uploadPaths = paths;

                if (serviceData.isOnlyUploadModifiedArtifacts()) {
                    List<FilePath> modifiedPathsList = new ArrayList<>();
                    for (FilePath path : paths) {
                        if (path.lastModified() >= serviceData.getRun().getStartTimeInMillis()) {
                            modifiedPathsList.add(path);
                        }
                    }
                    uploadPaths = modifiedPathsList.toArray(new FilePath[0]);
                }

                archiveIncludes.append(",").append(fileName);
                filesNeedUpload += uploadPaths.length;
                filesCount += paths.length;

                if (uploadPaths.length != 0 && serviceData.getUploadType() != UploadType.ZIP) {
                    // the uploadType is either INDIVIDUAL or BOTH, upload included individual files thus.
                    uploadIndividuals(embeddedVP, uploadPaths, workspacePath);
                }
            }

            // if uploadType is BOTH or ZIP, create an archive.zip and upload
            if (filesCount != 0 && (serviceData.getUploadType() != UploadType.INDIVIDUAL)) {
                uploadArchive(archiveIncludes.toString());
                // archive file should not be included in downloaded file count
                filesUploaded.decrementAndGet();
            }
            println(Messages.WAStoragePublisher_files_need_upload_count(filesNeedUpload));
            waitForUploadEnd();
        } catch (IOException | InterruptedException e) {
            throw new WAStorageException(e.getMessage(), e);
        }

        println(Messages.WAStoragePublisher_files_uploaded_count(filesUploaded.get()));
        return filesCount;
    }

    protected void waitForUploadEnd() throws InterruptedException, WAStorageException {
        executorService.shutdown();
        boolean executionFinished = executorService.awaitTermination(TIME_OUT, TIME_OUT_UNIT);
        if (!executionFinished) {
            throw new WAStorageException(Messages.WAStoragePublisher_uploaded_timeout(TIME_OUT, TIME_OUT_UNIT));
        }
    }

//    protected Future<> uploadBlob(String url, FilePath src, TaskListener listener) throws WAStorageException {

//        try {
//            final MessageDigest md = DigestUtils.getMd5Digest();
//            long startTime = System.currentTimeMillis();

//            HttpClient client = HttpUtils.getClient();
//            PutMethod method = HttpUtils.getStoragePutMethod(url, new File(src.getRemote()));
//            Future<?> submit = getExecutorService().submit(new UploadThread());

//            try (InputStream inputStream = src.read();
//                 DigestInputStream digestInputStream = new DigestInputStream(inputStream, md)) {
//
//                // send AI event.
//                AzureStoragePlugin.sendEvent(AppInsightsConstants.AZURE_BLOB_STORAGE, UPLOAD,
//                        "StorageAccount", "",
//                        "ContentLength", String.valueOf(src.length()));
//            }
//            long endTime = System.currentTimeMillis();
//
//            println("Uploaded to file storage with uri " + url + " in " + getTime(endTime - startTime));
//            return DatatypeConverter.printHexBinary(md.digest());
//        } catch (IOException | InterruptedException e) {
//            // send AI event.
//            AzureStoragePlugin.sendEvent(AppInsightsConstants.AZURE_BLOB_STORAGE, UPLOAD,
//                    "StorageAccount", "",
//                    "Message", e.getMessage());
//            throw new WAStorageException(e.getMessage(), e);
//        }
//    }

//    /**
//     * @param blob
//     * @param src
//     * @throws StorageException
//     * @throws IOException
//     * @throws InterruptedException
//     * @returns Md5 hash of the uploaded file in hexadecimal encoding
//     */
//    protected String uploadBlob(CloudBlockBlob blob, FilePath src)
//            throws WAStorageException {
//        String hashedStorageAcc = AppInsightsUtils.hash(blob.getServiceClient().getCredentials().getAccountName());
//        try {
//            final MessageDigest md = DigestUtils.getMd5Digest();
//            long startTime = System.currentTimeMillis();
//            try (InputStream inputStream = src.read();
//                 DigestInputStream digestInputStream = new DigestInputStream(inputStream, md)) {
//                blob.upload(
//                        digestInputStream,
//                        src.length(),
//                        null,
//                        getBlobRequestOptions(),
//                        Utils.updateUserAgent(src.length()));
//
//                // send AI event.
//                AzureStoragePlugin.sendEvent(AppInsightsConstants.AZURE_BLOB_STORAGE, UPLOAD,
//                        "StorageAccount", hashedStorageAcc,
//                        "ContentLength", String.valueOf(src.length()));
//            }
//            long endTime = System.currentTimeMillis();
//
//            println("Uploaded to file storage with uri " + blob.getUri() + " in " + getTime(endTime - startTime));
//            return DatatypeConverter.printHexBinary(md.digest());
//        } catch (IOException | InterruptedException | StorageException e) {
//            // send AI event.
//            AzureStoragePlugin.sendEvent(AppInsightsConstants.AZURE_BLOB_STORAGE, UPLOAD,
//                    "StorageAccount", hashedStorageAcc,
//                    "Message", e.getMessage());
//            throw new WAStorageException(e.getMessage(), e);
//        }
//    }

    protected String uploadCloudFile(CloudFile cloudFile, FilePath localPath)
            throws WAStorageException {
        String hashedStorageAcc = AppInsightsUtils.hash(cloudFile.getServiceClient().getCredentials().getAccountName());
        try {
            cloudFile.setMetadata(updateMetadata(cloudFile.getMetadata()));

            final MessageDigest md = DigestUtils.getMd5Digest();
            long startTime = System.currentTimeMillis();
            try (InputStream inputStream = localPath.read();
                 DigestInputStream digestInputStream = new DigestInputStream(inputStream, md)) {
                cloudFile.upload(
                        digestInputStream,
                        localPath.length(),
                        null,
                        new FileRequestOptions(),
                        Utils.updateUserAgent(localPath.length()));
            }
            long endTime = System.currentTimeMillis();

            // send AI event.
            AzureStoragePlugin.sendEvent(AppInsightsConstants.AZURE_FILE_STORAGE, UPLOAD,
                    "StorageAccount", hashedStorageAcc,
                    "ContentLength", String.valueOf(localPath.length()));

            println("Uploaded blob with uri " + cloudFile.getUri() + " in " + getTime(endTime - startTime));
            return DatatypeConverter.printHexBinary(md.digest());
        } catch (IOException | InterruptedException | StorageException | URISyntaxException e) {
            AzureStoragePlugin.sendEvent(AppInsightsConstants.AZURE_FILE_STORAGE, UPLOAD_FAILED,
                    "StorageAccount", hashedStorageAcc,
                    "Message", e.getMessage());
            throw new WAStorageException("fail to upload file to azure file storage", e);
        }
    }

    protected String excludedFilesAndZip() {
        final UploadServiceData serviceData = getServiceData();
        // Make sure we exclude the tempPath from archiving.
        String excludesWithoutZip = "**/" + ZIP_FOLDER_NAME + "*/" + ZIP_NAME;
        if (serviceData.getExcludedFilesPath() != null) {
            excludesWithoutZip = serviceData.getExcludedFilesPath() + "," + excludesWithoutZip;
        }
        return excludesWithoutZip;
    }

    /**
     * Convert the path on local file sytem to relative path on azure storage.
     *
     * @param path       the local path
     * @param embeddedVP the embedded virtual path
     * @return
     */
    protected String getItemPath(FilePath path, String embeddedVP)
            throws IOException, InterruptedException {
        final UploadServiceData serviceData = getServiceData();
        final URI workspaceURI = serviceData.getRemoteWorkspace().toURI();

        // Remove the workspace bit of this path
        final URI srcURI = workspaceURI.relativize(path.toURI());
        final String srcURIPath = srcURI.getPath();
        String prefix;
        if (StringUtils.isBlank(serviceData.getVirtualPath())) {
            prefix = "";
        } else {
            prefix = serviceData.getVirtualPath();
        }
        if (!StringUtils.isBlank(embeddedVP)) {
            prefix += embeddedVP;
        }

        return prefix + srcURIPath;
    }

    protected HashMap<String, String> updateMetadata(HashMap<String, String> metadata)
            throws IOException, InterruptedException {
        final UploadServiceData serviceData = getServiceData();
        final EnvVars env = serviceData.getRun().getEnvironment(serviceData.getTaskListener());

        if (serviceData.getAzureBlobMetadata() != null) {
            for (AzureBlobMetadataPair pair : serviceData.getAzureBlobMetadata()) {
                final String resolvedKey = Util.replaceMacro(pair.getKey(), env);
                final String resolvedValue = Util.replaceMacro(pair.getValue(), env);

                // Azure does not allow null, empty or whitespace metadata key
                if (resolvedKey == null || resolvedKey.trim().length() == 0) {
                    println("Ignoring blank metadata key");
                    continue;
                }

                // Azure does not allow null, empty or whitespace metadata value
                if (resolvedValue == null || resolvedValue.trim().length() == 0) {
                    println("Ignoring blank metadata value, key: " + resolvedKey);
                    continue;
                }

                metadata.put(resolvedKey, resolvedValue);
            }
        }

        return metadata;
    }

    public ExecutorService getExecutorService() {
        return executorService;
    }
}
