package com.seafile.seadroid2.enums;

public enum TransferDataSource {
    ALBUM_BACKUP,

    /**
     * (automatically)
     * folder backup
     */
    FOLDER_BACKUP,

    /**
     * (manually)
     * share_to_seafile/upload_from_local
     */
    FILE_BACKUP,

    DOWNLOAD;
}
