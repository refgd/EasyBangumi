package com.heyanle.easybangumi4.plugin.js.utils;


import com.heyanle.easybangumi4.plugin.api.utils.core.SourceUtils;

/**
 * Created by HeYanLe on 2024/11/3 20:54.
 * https://github.com/heyanLE
 */

public class JSSourceUtils {


    public static String urlParser(String rootURL, String source) {
        return SourceUtils.INSTANCE.urlParser(rootURL, source);
    }
}
