package com.seafile.seadroid2.framework.worker.download;

import android.content.Context;
import android.text.TextUtils;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.WorkerParameters;

import com.blankj.utilcode.util.CollectionUtils;
import com.blankj.utilcode.util.FileUtils;
import com.blankj.utilcode.util.ToastUtils;
import com.seafile.seadroid2.R;
import com.seafile.seadroid2.SeafException;
import com.seafile.seadroid2.account.Account;
import com.seafile.seadroid2.framework.crypto.Crypto;
import com.seafile.seadroid2.framework.data.Block;
import com.seafile.seadroid2.framework.data.db.entities.DirentModel;
import com.seafile.seadroid2.framework.data.db.entities.RepoModel;
import com.seafile.seadroid2.enums.TransferDataSource;
import com.seafile.seadroid2.framework.datastore.DataManager;
import com.seafile.seadroid2.framework.data.FileBlocks;
import com.seafile.seadroid2.framework.datastore.StorageManager;
import com.seafile.seadroid2.framework.data.db.AppDatabase;
import com.seafile.seadroid2.framework.data.db.entities.EncKeyCacheEntity;
import com.seafile.seadroid2.framework.data.db.entities.FileTransferEntity;
import com.seafile.seadroid2.enums.TransferResult;
import com.seafile.seadroid2.enums.TransferStatus;
import com.seafile.seadroid2.framework.http.HttpIO;
import com.seafile.seadroid2.framework.notification.base.BaseNotification;
import com.seafile.seadroid2.framework.util.SLogs;
import com.seafile.seadroid2.framework.worker.BackgroundJobManagerImpl;
import com.seafile.seadroid2.framework.worker.ExistingFileStrategy;
import com.seafile.seadroid2.framework.worker.TransferEvent;
import com.seafile.seadroid2.framework.worker.TransferWorker;
import com.seafile.seadroid2.listener.FileTransferProgressListener;
import com.seafile.seadroid2.framework.notification.DownloadNotificationHelper;
import com.seafile.seadroid2.ui.file.FileService;
import com.seafile.seadroid2.framework.worker.body.MonitoredFileOutputStream;

import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Worker Tag:
 *
 * @see BackgroundJobManagerImpl#TAG_ALL
 * @see BackgroundJobManagerImpl#TAG_TRANSFER
 */
public class DownloadWorker extends BaseDownloadWorker {
    public static final UUID UID = UUID.nameUUIDFromBytes(DownloadWorker.class.getSimpleName().getBytes());

    private final DownloadNotificationHelper notificationHelper;
    private final FileTransferProgressListener fileTransferProgressListener = new FileTransferProgressListener();

    @Override
    public BaseNotification getNotification() {
        return notificationHelper;
    }

    public DownloadWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);

        notificationHelper = new DownloadNotificationHelper(context);
        fileTransferProgressListener.setProgressListener(progressListener);
    }

    @Override
    public void onStopped() {
        super.onStopped();
    }

    @NonNull
    @Override
    public Result doWork() {

        Account account = getCurrentAccount();
        if (account == null) {
            return Result.success();
        }

        //count
        int pendingCount = AppDatabase.getInstance().fileTransferDAO().countPendingDownloadListSync(account.getSignature());
        if (pendingCount <= 0) {
            SLogs.i("download list is empty.");
            return Result.success(getFinishData(false));
        }

        ForegroundInfo foregroundInfo = notificationHelper.getForegroundNotification();
        showForegroundAsync(foregroundInfo);

        //tip
        String tip = getApplicationContext().getResources().getQuantityString(R.plurals.transfer_download_started, pendingCount, pendingCount);
        ToastUtils.showLong(tip);

        //start download
        boolean isDownloaded = false;
        while (true) {

            if (isStopped()) {
                break;
            }

            List<FileTransferEntity> list = AppDatabase
                    .getInstance()
                    .fileTransferDAO()
                    .getOnePendingDownloadByAccountSync(account.getSignature());
            if (CollectionUtils.isEmpty(list)) {
                break;
            }

            isDownloaded = true;

            FileTransferEntity transferEntity = list.get(0);

            try {
                transferFile(account, transferEntity);

                sendTransferEvent(transferEntity, true);

            } catch (Exception e) {
                isDownloaded = false;

                TransferResult transferResult = onException(transferEntity, e);

                //
                notifyError(transferResult);

                sendTransferEvent(transferEntity, false);

                String finishFlag = isInterrupt(transferResult);
                if (!TextUtils.isEmpty(finishFlag)) {
                    break;
                }
            }
        }

        SLogs.i("all task run");

        //
        if (isDownloaded) {
            ToastUtils.showLong(R.string.download_finished);
        }

        return Result.success(getFinishData(isDownloaded));
    }

    private Data getFinishData(boolean isDownloaded) {
        return new Data.Builder()
                .putString(TransferWorker.KEY_DATA_EVENT, TransferEvent.EVENT_FINISH)
                .putBoolean(TransferWorker.KEY_DATA_PARAM, isDownloaded)
                .putString(TransferWorker.KEY_DATA_TYPE, String.valueOf(TransferDataSource.DOWNLOAD))
                .build();
    }

    /**
     *
     */
    private final FileTransferProgressListener.TransferProgressListener progressListener = new FileTransferProgressListener.TransferProgressListener() {
        @Override
        public void onProgressNotify(FileTransferEntity fileTransferEntity, int percent, long transferredSize, long totalSize) {
            SLogs.i(fileTransferEntity.file_name + " -> progress：" + percent);

            int diff = AppDatabase.getInstance().fileTransferDAO().countPendingDownloadListSync(fileTransferEntity.related_account);

            ForegroundInfo foregroundInfo = notificationHelper.getForegroundProgressNotification(fileTransferEntity.file_name, percent, diff);
            showForegroundAsync(foregroundInfo);

            //
            AppDatabase.getInstance().fileTransferDAO().update(fileTransferEntity);

            //
            sendProgressNotifyEvent(fileTransferEntity.file_name, fileTransferEntity.uid, percent, transferredSize, totalSize, fileTransferEntity.data_source);
        }
    };

    private void transferFile(Account account, FileTransferEntity transferEntity) throws Exception {
        SLogs.i("download start：" + transferEntity.full_path);

        //show notification
        int diff = AppDatabase.getInstance().fileTransferDAO().countPendingDownloadListSync(transferEntity.related_account);
        ForegroundInfo foregroundInfo = notificationHelper.getForegroundProgressNotification(transferEntity.file_name, 0, diff);
        showForegroundAsync(foregroundInfo);

        List<RepoModel> repoModels = AppDatabase.getInstance().repoDao().getByIdSync(transferEntity.repo_id);

        if (CollectionUtils.isEmpty(repoModels)) {
            SLogs.i("no repo for repoId: " + transferEntity.repo_id);
            return;
        }

        //update modified_at field
        transferEntity.modified_at = System.currentTimeMillis();
        AppDatabase.getInstance().fileTransferDAO().update(transferEntity);

        if (repoModels.get(0).canLocalDecrypt()) {
            downloadFileByBlock(account, transferEntity);
        } else {
            downloadFile(account, transferEntity);
        }
    }

    private void downloadFile(Account account, FileTransferEntity transferEntity) throws Exception {

        Pair<String, String> pair = getDownloadLink(transferEntity, false);
        String dlink = pair.first;
        String fileId = pair.second;

        File localFile = DataManager.getLocalRepoFile(account, transferEntity);

        if (localFile.exists() && transferEntity.file_strategy == ExistingFileStrategy.SKIP) {
            SLogs.i("skip this file, file_strategy is SKIP ：" + localFile.getAbsolutePath());
            return;
        }

        download(transferEntity, dlink, localFile);

        SLogs.i("download finish：" + transferEntity.full_path);
    }

    private Pair<String, String> getDownloadLink(FileTransferEntity transferEntity, boolean isReUsed) throws SeafException, IOException {
        retrofit2.Response<String> res = HttpIO.getCurrentInstance()
                .execute(FileService.class)
                .getFileDownloadLink(transferEntity.repo_id, transferEntity.full_path)
                .execute();

        if (!res.isSuccessful()) {
            throw SeafException.networkException;
        }

        String fileId = res.headers().get("oid");
        String dlink = res.body();
        if (dlink == null) {
            throw SeafException.networkException;
        }

        dlink = StringUtils.replace(dlink, "\"", "");
        int i = dlink.lastIndexOf('/');
        if (i == -1) {
            // Handle invalid dlink appropriately
            return null;
        }

        dlink = dlink.substring(0, i) + "/" + URLEncoder.encode(dlink.substring(i + 1), "UTF-8");

        // should return "\"http://gonggeng.org:8082/...\"" or "\"https://gonggeng.org:8082/...\"
        if (dlink.startsWith("http") && fileId != null) {
            return new Pair<>(dlink, fileId);
        } else {
            throw SeafException.illFormatException;
        }
    }

    private void download(FileTransferEntity fileTransferEntity, String dlink, File localFile) throws Exception {
        fileTransferProgressListener.setFileTransferEntity(fileTransferEntity);

        fileTransferEntity.transfer_status = TransferStatus.IN_PROGRESS;
        AppDatabase.getInstance().fileTransferDAO().update(fileTransferEntity);

        Request request = new Request.Builder()
                .url(dlink)
                .get()
                .build();

        Call newCall = HttpIO.getCurrentInstance().getOkHttpClient().getOkClient().newCall(request);

        try (Response response = newCall.execute()) {
            if (!response.isSuccessful()) {
                throw SeafException.networkException;
            }

            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw SeafException.networkException;
            }

            long fileSize = responseBody.contentLength();
            if (fileSize == -1) {
                SLogs.e("download file error -> contentLength is -1");
                SLogs.e(localFile.getAbsolutePath());

                fileSize = fileTransferEntity.file_size;

//                updateEntityErrorState(fileTransferEntity);
//                return;
            }

            File tempFile = DataManager.createTempFile();
            try (InputStream inputStream = responseBody.byteStream();
                 FileOutputStream fileOutputStream = new FileOutputStream(tempFile)) {

                long totalBytesRead = 0;

                int bytesRead;
                byte[] buffer = new byte[SEGMENT_SIZE];
                while ((bytesRead = inputStream.read(buffer, 0, buffer.length)) != -1) {
                    if (isStopped()) {
                        throw SeafException.userCancelledException;
                    }

                    fileOutputStream.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;

                    //notify Notification and update DB
                    fileTransferProgressListener.onProgressNotify(totalBytesRead, fileSize);
                }

                //notify complete
                fileTransferProgressListener.onProgressNotify(fileSize, fileSize);
            }

            //important
            tempFile.renameTo(localFile);
//            Path path = java.nio.file.Files.move(tempFile.toPath(), localFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
//            boolean isSuccess = path.toFile().exists();

            if (localFile.length() != fileSize) {
                SLogs.e("download file error -> localFile.size != downloadedSize");
                SLogs.e(localFile.getAbsolutePath());
                updateEntityErrorState(fileTransferEntity);
            } else {
                updateEntitySuccessState(fileTransferEntity, localFile);
            }
        }
    }

    private void updateEntityErrorState(FileTransferEntity fileTransferEntity) {
        fileTransferEntity.transfer_status = TransferStatus.FAILED;
        fileTransferEntity.transfer_result = TransferResult.FILE_ERROR;
        AppDatabase.getInstance().fileTransferDAO().update(fileTransferEntity);
    }

    private void updateEntitySuccessState(FileTransferEntity fileTransferEntity, File localFile) {
        fileTransferEntity.transferred_size = localFile.length();
        fileTransferEntity.transfer_result = TransferResult.TRANSMITTED;
        fileTransferEntity.transfer_status = TransferStatus.SUCCEEDED;
        fileTransferEntity.action_end_at = System.currentTimeMillis();
        fileTransferEntity.file_original_modified_at = fileTransferEntity.action_end_at;//now
        fileTransferEntity.file_size = localFile.length();
        fileTransferEntity.file_md5 = FileUtils.getFileMD5ToString(fileTransferEntity.target_path).toLowerCase();

        AppDatabase.getInstance().fileTransferDAO().update(fileTransferEntity);

        //update
        List<DirentModel> direntList = AppDatabase.getInstance().direntDao().getListByFullPathSync(fileTransferEntity.repo_id, fileTransferEntity.full_path);
        if (!CollectionUtils.isEmpty(direntList)) {
            DirentModel direntModel = direntList.get(0);
            direntModel.last_modified_at = fileTransferEntity.modified_at;
            direntModel.id = fileTransferEntity.file_id;
            direntModel.size = fileTransferEntity.file_size;
            direntModel.transfer_status = fileTransferEntity.transfer_status;

            AppDatabase.getInstance().direntDao().insert(direntModel);
        }

    }

    ///////////////block///////////////
    private FileBlocks getDownloadBlockList(FileTransferEntity transferEntity) throws Exception {
        retrofit2.Response<FileBlocks> res = HttpIO.getCurrentInstance()
                .execute(FileService.class)
                .getFileBlockDownloadLink(transferEntity.repo_id, transferEntity.full_path)
                .execute();

        if (!res.isSuccessful()) {
            throw SeafException.networkException;
        }

        FileBlocks fileBlocks = res.body();
        if (fileBlocks == null) {
            throw SeafException.networkException;
        }

        return fileBlocks;
    }

    private void downloadFileByBlock(Account account, FileTransferEntity transferEntity) throws Exception {

        File localFile = DataManager.getLocalRepoFile(account, transferEntity);
        if (localFile.exists() && transferEntity.file_strategy == ExistingFileStrategy.SKIP) {
            SLogs.i("skip this file, file_strategy is SKIP ：" + localFile.getAbsolutePath());
            return;
        }

        FileBlocks fileBlocks = getDownloadBlockList(transferEntity);

        List<EncKeyCacheEntity> encKeyCacheEntityList = AppDatabase.getInstance().encKeyCacheDAO().getOneByRepoIdSync(transferEntity.repo_id);

        if (CollectionUtils.isEmpty(encKeyCacheEntityList)) {
            throw SeafException.decryptException;
        }
        EncKeyCacheEntity entity = encKeyCacheEntityList.get(0);

        final String encKey = entity.enc_key;
        final String encIv = entity.enc_iv;
        if (TextUtils.isEmpty(encKey) || TextUtils.isEmpty(encIv)) {
            throw SeafException.decryptException;
        }

        //TODO
//        if (CollectionUtils.isEmpty(fileBlocks.blocks)) {
//            if (!localFile.createNewFile()) {
//                SLogs.w( "Failed to create file " + localFile.getName());
//                return;
//            }
//
//            addCachedFile(repoName, repoID, path, fileBlocks.fileID, localFile);
//            return localFile;
//        }

        fileTransferProgressListener.setFileTransferEntity(transferEntity);

        List<File> tempFileList = new ArrayList<>();
        for (Block blk : fileBlocks.getBlocks()) {
            File tempBlock = new File(StorageManager.getInstance().getTempDir(), blk.blockId);

            retrofit2.Response<String> blockRes = HttpIO.getCurrentInstance()
                    .execute(FileService.class)
                    .getBlockDownloadLink(transferEntity.repo_id, fileBlocks.getFileId(), blk.blockId)
                    .execute();

            if (!blockRes.isSuccessful()) {
                throw SeafException.networkException;
            }

            String dlink = blockRes.body();
            dlink = StringUtils.replace(dlink, "\"", "");

            downloadBlock(fileBlocks, blk.blockId, dlink, tempBlock, transferEntity.file_size);

            final byte[] bytes = org.apache.commons.io.FileUtils.readFileToByteArray(tempBlock);
            final byte[] decryptedBlock = Crypto.decrypt(bytes, encKey, encIv);
            org.apache.commons.io.FileUtils.writeByteArrayToFile(localFile, decryptedBlock, true);

            tempFileList.add(tempBlock);
        }

        //remove cache file
        tempFileList.forEach(File::delete);

        //
        updateEntitySuccessState(transferEntity, localFile);
    }


    private void downloadBlock(FileBlocks fileBlocks, String blockId, String dlink, File localFile, long fileSize) throws Exception {
        InputStream inputStream = null;
        MonitoredFileOutputStream monitoredFileOutputStream = null;
        try {

            Request request = new Request.Builder()
                    .url(dlink)
                    .get()
                    .build();
            Call newCall = HttpIO.getCurrentInstance().getOkHttpClient().getOkClient().newCall(request);

            Response response = newCall.execute();

            if (!response.isSuccessful()) {
                throw SeafException.networkException;
            }

            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw SeafException.networkException;
            }

            long tempFileSize = responseBody.contentLength();

            inputStream = responseBody.byteStream();
            monitoredFileOutputStream = new MonitoredFileOutputStream(fileBlocks, blockId, localFile, fileSize, fileTransferProgressListener);


            int bytesRead;
            byte[] buffer = new byte[SEGMENT_SIZE];
            while ((bytesRead = inputStream.read(buffer, 0, buffer.length)) != -1) {
                if (isStopped()) {
                    throw SeafException.userCancelledException;
                }
                monitoredFileOutputStream.write(buffer, 0, bytesRead);
            }

            responseBody.close();

            if (localFile.length() != tempFileSize) {
                SLogs.i("Rename file error : " + localFile.getAbsolutePath());
                throw SeafException.networkException;
            }

        } finally {
            if (monitoredFileOutputStream != null) {
                monitoredFileOutputStream.close();
            }
            if (inputStream != null) {
                inputStream.close();
            }
        }
    }

}
