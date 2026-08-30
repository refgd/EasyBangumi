package com.heyanle.easybangumi4.plugin.js.source

import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Base64
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Javascript
import com.heyanle.easybangumi4.plugin.api.IconSource
import com.heyanle.easybangumi4.plugin.api.Source
import com.heyanle.easybangumi4.plugin.js.extension.JSExtensionLoader
import com.heyanle.easybangumi4.plugin.js.runtime.JSRuntimeProvider
import java.io.File
import kotlin.reflect.KClass

/**
 * Created by heyanle on 2024/7/27.
 * https://github.com/heyanLE
 */
class JsSource(
    val map: Map<String, String>,
    val js: Any,
    val jsRuntime: JSRuntimeProvider,
): Source, AsyncIconSource, IconSource {

    companion object {
        const val JS_IMPORT = """
            importPackage(Packages.com.heyanle.easybangumi4.plugin.extension);
            importPackage(Packages.com.heyanle.easybangumi4.plugin.js.runtime);
            importPackage(Packages.com.heyanle.easybangumi4.plugin.js.entity);
            importPackage(Packages.com.heyanle.easybangumi4.plugin.api);
            importPackage(Packages.com.heyanle.easybangumi4.plugin.api.utils.api);
            importPackage(Packages.com.heyanle.easybangumi4.plugin.api.entity);
            importPackage(Packages.com.heyanle.easybangumi4.plugin.js.utils);
            importPackage(Packages.com.heyanle.easybangumi4.plugin.api.component.preference);
            
            importPackage(Packages.kotlin.text);
            importPackage(Packages.kotlin);
            
            importPackage(Packages.java.util);
            importPackage(Packages.java.lang);
            importPackage(Packages.java.net);
            
            importPackage(Packages.org.jsoup);
            importPackage(Packages.okhttp3);
           
            
            var Log = function(type, data) {
                if(!data) {
                    data = type;
                    type = "DEBUG";
                }
                com.heyanle.easybangumi4.plugin.source.Debug.INSTANCE.log(Inject_Source.key, type+": "+JSON.stringify(data), true, true, 1);
            }

            var DebugCapture = function(label, data) {
                var content = typeof data === "string" ? data : JSON.stringify(data);
                com.heyanle.easybangumi4.plugin.source.Debug.INSTANCE.capture(
                    Inject_Source.key,
                    String(label || "调试原文"),
                    String(content || "")
                );
            }

            function __ebgToJavaString(value) {
                return new Packages.java.lang.String(value == null ? "" : String(value));
            }

            function __ebgToNullableJavaString(value) {
                return value == null ? null : __ebgToJavaString(value);
            }

            function __ebgToJavaInteger(value, fallback) {
                var number = parseInt(value, 10);
                if (isNaN(number)) {
                    number = parseInt(fallback, 10);
                }
                if (isNaN(number)) {
                    number = 0;
                }
                return new Packages.java.lang.Integer(number);
            }

            function __ebgToJavaArrayList(values) {
                if (values instanceof Packages.java.util.ArrayList) {
                    return values;
                }
                var result = new ArrayList();
                if (values == null) {
                    return result;
                }
                for (var i = 0; i < values.length; i++) {
                    result.add(values[i]);
                }
                return result;
            }

            function __ebgToJavaStringArrayList(values) {
                var result = new ArrayList();
                if (values == null) {
                    return result;
                }
                for (var i = 0; i < values.length; i++) {
                    result.add(__ebgToJavaString(values[i]));
                }
                return result;
            }

            function __ebgToBoolean(value, fallback) {
                if (value == null) return fallback;
                if (typeof value === "string") return value.toLowerCase() === "true";
                return Boolean(value);
            }

            function __ebgToNumber(value, fallback) {
                var number = Number(value);
                return isNaN(number) ? fallback : number;
            }

            function makeEpisode(map) {
                return new Episode(
                    __ebgToJavaString(map.id),
                    __ebgToJavaString(map.label),
                    __ebgToJavaInteger(map.order, 0)
                );
            }

            function makePlayLine(map) {
                return new PlayLine(
                    __ebgToJavaString(map.id),
                    __ebgToJavaString(map.label),
                    __ebgToJavaArrayList(map.episodes)
                );
            }

            function makePageResult(nextKey, items) {
                var javaNextKey = nextKey == null ? null : __ebgToJavaInteger(nextKey, 0);
                return new Pair(javaNextKey, __ebgToJavaArrayList(items));
            }

            function makeDetailedResult(cartoon, playLines) {
                return new Pair(cartoon, __ebgToJavaArrayList(playLines));
            }

            function makePlayerInfo(map) {
                var player = new PlayerInfo(
                    __ebgToJavaInteger(map.decodeType, PlayerInfo.DECODE_TYPE_OTHER),
                    __ebgToJavaString(map.uri)
                );
                if (map.headers != null) {
                    var headers = new HashMap();
                    for (var key in map.headers) {
                        if (Object.prototype.hasOwnProperty.call(map.headers, key)) {
                            headers.put(__ebgToJavaString(key), __ebgToJavaString(map.headers[key]));
                        }
                    }
                    player.header = headers;
                }
                var options = map.hlsOptions;
                if (options != null) {
                    var hlsOptions = player.getHlsOptions();
                    if (options.segmentPayload != null) hlsOptions.segmentPayload = __ebgToJavaString(options.segmentPayload);
                    if (options.filterMinorityHosts != null) hlsOptions.filterMinorityHosts = __ebgToBoolean(options.filterMinorityHosts, true);
                    if (options.minorityHostThreshold != null) hlsOptions.minorityHostThreshold = __ebgToNumber(options.minorityHostThreshold, 0.15);
                    if (options.maxAdDurationSeconds != null) hlsOptions.maxAdDurationSeconds = __ebgToNumber(options.maxAdDurationSeconds, 180);
                    if (options.blockedSegmentRegex != null) {
                        hlsOptions.blockedSegmentRegex = __ebgToJavaStringArrayList(options.blockedSegmentRegex);
                    }
                }
                return player;
            }
            
            function makeCartoonCover(map) {
                var id = __ebgToJavaString(map.id);
                var source = Inject_Source.key;
                var url = __ebgToJavaString(map.url);
                var title = __ebgToJavaString(map.title);
                var intro = __ebgToNullableJavaString(map.intro);
                var cover = __ebgToNullableJavaString(map.cover);
                return new CartoonCoverImpl(id, source, url, title, intro, cover);
            }
            
            function makeCartoon(map) {
                var id = __ebgToJavaString(map.id);
                var source = Inject_Source.key;
                var url = __ebgToJavaString(map.url);
                
                var title = __ebgToJavaString(map.title);
                
                
                var genre = null;
                var coverUrl = null;
                var intro = null;
                var description = null;
                var updateStrategy = 0;
                var isUpdate = false;
                var status = 0;
                
                if (map.genreList != undefined) {
                    var stringBuilder = new StringBuilder();
                    for (var i = 0 ; i < map.genreList.length; i++) {
                        stringBuilder.append(map.genreList[i]);
                        if(i != map.genreList.length - 1) {
                            stringBuilder.append(", ");
                        }
                    }
                    genre = stringBuilder.toString();
                }
                
                if (map.genre != undefined) {
                    genre = __ebgToJavaString(map.genre);
                }
                
              
                if (map.cover != undefined) {
                    coverUrl = __ebgToJavaString(map.cover);
                }
                
                if (map.intro != undefined) {
                    intro = __ebgToJavaString(map.intro);
                }
                
               
                if (map.description != undefined) {
                    description = __ebgToJavaString(map.description);
                }
                
                if (map.updateStrategy != undefined) {
                    updateStrategy = __ebgToJavaInteger(map.updateStrategy, 0);
                }
                
                if (map.isUpdate != undefined) {
                    isUpdate = map.isUpdate;
                }
                
                if (map.status != undefined) {
                    status = __ebgToJavaInteger(map.status, 0);
                }
                
                return new CartoonImpl(
                    id, source, url, title, genre, coverUrl, intro, description, updateStrategy, isUpdate, status
                );
            }
            
            function makeTextDanmaku(map) {
                var textData = new Packages.com.bytedance.danmaku.render.engine.render.draw.text.TextData()
                
                if (map.text != undefined) {
                    textData.text = map.text;
                }
                if (map.showAtTime != undefined) {
                    textData.showAtTime = map.showAtTime;
                }
                if (map.textSize != undefined) {
                    textData.textSize = map.textSize;
                }
                if (map.textColor != undefined) {
                    textData.textColor = map.textColor;
                }
                if (map.typeface != undefined) {
                    textData.typeface = map.typeface;
                }
                if (map.textStrokeWidth != undefined) {
                    textData.textStrokeWidth = map.textStrokeWidth;
                }
                if (map.textStrokeColor != undefined) {
                    textData.textStrokeColor = map.textStrokeColor;
                }
                if (map.includeFontPadding != undefined) {
                    textData.includeFontPadding = map.includeFontPadding;
                }
                if (map.hasUnderline != undefined) {
                    textData.hasUnderline = map.hasUnderline;
                }
                if (map.layerType != undefined) {
                    textData.layerType = map.layerType;
                }else{
                    textData.layerType = 1001;
                }
                
                return textData;
            }
        """
    }


    fun getJsString(): String {
        return if (js is File) {
            js.readText()
        } else {
            js.toString()
        }
    }

    fun getJsFile(): File? {
        return js as? File
    }


    override val describe: String?
        get() = map.get("describe")
    override val key: String
        get() = map.get(JSExtensionLoader.JS_SOURCE_TAG_KEY) ?: ""
    override val label: String
        get() = map.get(JSExtensionLoader.JS_SOURCE_TAG_LABEL) ?: ""
    override val version: String
        get() = map.get(JSExtensionLoader.JS_SOURCE_TAG_VERSION_NAME) ?: ""
    override val versionCode: Int
        get() = map.get(JSExtensionLoader.JS_SOURCE_TAG_VERSION_CODE)?.toIntOrNull() ?: 0
    override val hasPref: Int
        get() = map.get(JSExtensionLoader.JS_SOURCE_HAS_PREF)?.toIntOrNull() ?: 0
    override val hasSearch: Int
        get() = map.get(JSExtensionLoader.JS_SOURCE_HAS_SEARCH)?.toIntOrNull() ?: 0
    override val sourcePath: String
        get() = map.get("sourcePath") ?: ""


    // 轻量级插件的业务注册交给 JSComponentBundle 处理
    override fun register(): List<KClass<*>> {
        return emptyList()
    }

    override fun getAsyncIconData(): Any {
        val cover = map.get(JSExtensionLoader.JS_SOURCE_TAG_COVER) ?: return Icons.Filled.Javascript
        // url
        val uri = Uri.parse(cover)
        uri.scheme?.let {
            if(it == "http" || it == "https" || it == "content" || it == "file"){
                return uri
            }
        }
        // base64
        try {
            return Base64.decode(cover, Base64.DEFAULT)
        }catch (e: Throwable) {
            e.printStackTrace()
        }

        return Icons.Filled.Javascript
    }

    override fun getIconFactory(): () -> Drawable? {
        return { null }
    }


}
