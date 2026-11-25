package com.ghmanager.app;

import android.util.Base64;

import com.ghmanager.app.GitHubApi.Addition;
import com.ghmanager.app.GitHubApi.ApiException;
import com.ghmanager.app.GitHubApi.BlobInfo;
import com.ghmanager.app.GitHubApi.HeadMoved;
import com.ghmanager.app.GitHubApi.Piece;
import com.ghmanager.app.GitHubApi.Progress;
import com.ghmanager.app.GitHubApi.TextResult;
import com.ghmanager.app.GitHubApi.TreeEntry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.ghmanager.app.ApiCore.*;

final class DownloadApi extends ApiPart {
    DownloadApi(ApiCore core) {
        super(core);
    }

    /** Reads a text download, keeping only the last maxBytes bytes. */
    public TextResult readTail(String path, String accept, int maxBytes) throws Exception {
        HttpURLConnection c = openDownload(path, accept);
        try {
            InputStream is = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            final int hardCap = 24 * 1024 * 1024;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
                if (bos.size() > hardCap) break;
            }
            is.close();
            byte[] all = bos.toByteArray();
            boolean cut = false;
            if (all.length > maxBytes) {
                all = Arrays.copyOfRange(all, all.length - maxBytes, all.length);
                cut = true;
            }
            return new TextResult(new String(all, StandardCharsets.UTF_8), cut);
        } finally {
            c.disconnect();
        }
    }

    public Piece readFrom(String path, String accept, long from) throws Exception {
        HttpURLConnection c;
        rangeFrom(from);
        try {
            c = openDownload(path, accept);
        } finally {
            rangeFrom(0);
        }
        try {
            boolean ranged = from > 0 && c.getResponseCode() == 206;
            InputStream is = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            final int hardCap = 24 * 1024 * 1024;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
                if (bos.size() > hardCap) break;
            }
            is.close();
            return new Piece(bos.toByteArray(), ranged);
        } finally {
            c.disconnect();
        }
    }
}
