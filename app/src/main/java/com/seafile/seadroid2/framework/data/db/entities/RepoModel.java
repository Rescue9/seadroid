package com.seafile.seadroid2.framework.data.db.entities;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.room.Entity;

import com.google.gson.annotations.JsonAdapter;
import com.seafile.seadroid2.R;
import com.seafile.seadroid2.framework.data.model.BaseModel;
import com.seafile.seadroid2.framework.data.model.adapter.EncryptFieldJsonAdapter;
import com.seafile.seadroid2.framework.util.Utils;

@Entity(tableName = "repos", primaryKeys = {"repo_id", "group_id"})
public class RepoModel extends BaseModel {

    @NonNull
    public String repo_id = "";
    public String repo_name;   //repo_name

    public String type;   //mine\group\shared

    public long group_id;
    public String group_name;

    public String owner_name;

    /**
     * xxx@auth.local
     */
    public String owner_email;

    /**
     * xxx@xxx.com
     */
    public String owner_contact_email;

    public String modifier_email;
    public String modifier_name;
    public String modifier_contact_email;


    public String related_account;  //related account

    public String last_modified;

    @JsonAdapter(EncryptFieldJsonAdapter.class)
    public boolean encrypted;

    public long size;
    public boolean starred;

    public String permission;
    public boolean monitored;
    public boolean is_admin;
    public String salt;
    public String status;

    public long last_modified_long;


    //
    public String root;
    public String magic;

    public String random_key;
    public int enc_version;
    public int file_count;

    public String getSubtitle() {

        String subTitle = Utils.readableFileSize(size) + " · " + Utils.translateCommitTime(last_modified_long);
        if ("shared".equals(type)) {
            subTitle += " · " + owner_name;
        }

        return subTitle;
    }

    public int getIcon() {
        if (encrypted)
            return R.drawable.baseline_repo_encrypted_24;
        if (!hasWritePermission())
            return R.drawable.baseline_repo_readonly_24;

        return R.drawable.baseline_repo_24;
    }

    public boolean hasWritePermission() {
        if (TextUtils.isEmpty(permission)) {
            return false;
        }

        if (permission.equals("cloud-edit")) {
            return false;
        }

        if (permission.equals("preview")) {
            return false;
        }

        return !TextUtils.isEmpty(permission) && permission.contains("w");
    }


//    /**
//     * If the result is true, and the decryption was successful,
//     * there is definitely one row of data in the {@link EncKeyCacheEntity}
//     */
//    public boolean canLocalDecrypt() {
//        return encrypted
//                && enc_version == SettingsManager.REPO_ENC_VERSION
//                && !TextUtils.isEmpty(magic)
//                && ClientEncryptSharePreferenceHelper.isEncryptEnabled();
//    }

    /**
     * new feature at 2024/10/22 with v3.0.5
     */
    public boolean canLocalDecrypt() {
        return false;
    }
}
