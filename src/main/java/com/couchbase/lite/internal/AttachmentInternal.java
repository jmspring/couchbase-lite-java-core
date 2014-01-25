package com.couchbase.lite.internal;

import com.couchbase.lite.BlobKey;

/**
 * A simple container for attachment metadata.
 */
public class AttachmentInternal {

    public enum AttachmentEncoding {
        AttachmentEncodingNone, AttachmentEncodingGZIP
    }

    private String name;
    private String contentType;

    private BlobKey blobKey;
    private long length;
    private long encodedLength;
    private AttachmentEncoding encoding;
    private int revpos;

    public AttachmentInternal(String name, String contentType) {
        this.name = name;
        this.contentType = contentType;
    }

    public boolean isValid() {
        if (encoding != AttachmentEncoding.AttachmentEncodingNone) {
            if (encodedLength == 0 && length > 0) {
                return false;
            }
        }
        else if (encodedLength > 0) {
            return false;
        }
        if (revpos == 0) {
            return false;
        }
        return true;
    }

    public String getName() {
        return name;
    }

    public String getContentType() {
        return contentType;
    }

    public BlobKey getBlobKey() {
        return blobKey;
    }

    public void setBlobKey(BlobKey blobKey) {
        this.blobKey = blobKey;
    }

    public long getLength() {
        return length;
    }

    public void setLength(long length) {
        this.length = length;
    }

    public long getEncodedLength() {
        return encodedLength;
    }

    public void setEncodedLength(long encodedLength) {
        this.encodedLength = encodedLength;
    }

    public AttachmentEncoding getEncoding() {
        return encoding;
    }

    public void setEncoding(AttachmentEncoding encoding) {
        this.encoding = encoding;
    }

    public int getRevpos() {
        return revpos;
    }

    public void setRevpos(int revpos) {
        this.revpos = revpos;
    }


}

