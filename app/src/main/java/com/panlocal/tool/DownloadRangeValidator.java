package com.panlocal.tool;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict HTTP byte-range response validator for large Android downloads. */
public final class DownloadRangeValidator {
    private static final Pattern RANGE=Pattern.compile("^bytes[ \\t]+([0-9]+)-([0-9]+)/([0-9]+)$");
    private DownloadRangeValidator(){}

    /**
     * Validates both the requested absolute offset and authoritative file size.
     * A partial response for another file, wrong offset or wrong total is rejected
     * before any bytes are written to the device document.
     */
    public static long validate(int status,long requestedOffset,long expectedBytes,
                                String contentRange,long contentLength){
        if(requestedOffset<0||expectedBytes<=0||requestedOffset>=expectedBytes)
            throw new IllegalArgumentException("invalid_expected_file_bounds");
        if(status==200){
            if(requestedOffset!=0)throw new IllegalArgumentException("resume_not_supported");
            if(contentLength>=0&&contentLength!=expectedBytes)
                throw new IllegalArgumentException("full_body_length_mismatch");
            return expectedBytes;
        }
        if(status!=206)throw new IllegalArgumentException("unexpected_download_status");
        if(contentRange==null)throw new IllegalArgumentException("missing_content_range");
        Matcher match=RANGE.matcher(contentRange.trim());
        if(!match.matches())throw new IllegalArgumentException("malformed_content_range");
        final long start,end,total;
        try{
            start=Long.parseLong(match.group(1));
            end=Long.parseLong(match.group(2));
            total=Long.parseLong(match.group(3));
        }catch(NumberFormatException ex){
            throw new IllegalArgumentException("out_of_bounds_content_range",ex);
        }
        if(start!=requestedOffset)throw new IllegalArgumentException("range_start_mismatch");
        if(total!=expectedBytes)throw new IllegalArgumentException("remote_file_size_changed");
        if(end<start||end>=total)throw new IllegalArgumentException("range_end_mismatch");
        long size=end-start+1;
        if(contentLength>=0&&contentLength!=size)
            throw new IllegalArgumentException("partial_body_length_mismatch");
        return end+1;
    }
}
