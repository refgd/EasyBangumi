// @key refgd.cms
// @label 资源站通用
// @versionName 1.1
// @versionCode 2
// @libVersion 11
// @cover https://raw.githubusercontent.com/refgd/easybangumi-ext/refs/heads/main/cms/cms.png

/* eslint-disable no-unused-vars */

// Inject
var networkHelper = Inject_NetworkHelper;
var preferenceHelper = Inject_PreferenceHelper;

// Hook PreferenceComponent ========================================
function PreferenceComponent_getPreference() {
    var res = new ArrayList();
    var BaseUrl = new SourcePreference.Edit("JSON采集接口", "BaseUrl", "https://json.heimuer.xyz/api.php/provide/vod/?ac=list");
    var AdUrl = new SourcePreference.Edit("去广告接口", "AdUrl", "");
    res.add(BaseUrl);
    res.add(AdUrl);
    return res;
}

// Hook PageComment  ========================================
function PageComponent_getMainTabs() {
    var res = new ArrayList();
    res.add(new MainTab("首页", MainTab.MAIN_TAB_WITH_COVER));
    var arr = MainUtil.getVideoCategories();
    arr.forEach(function(e) {
        res.add(new MainTab(e.name, MainTab.MAIN_TAB_GROUP, e.id));
    });

    return res;
}

function PageComponent_getSubTabs(mainTab) {
    var res = new ArrayList();
    if (mainTab.ext && MainUtil.cates[mainTab.ext]) {
        if(MainUtil.cates[mainTab.ext].subs && MainUtil.cates[mainTab.ext].subs.length > 0){
            MainUtil.cates[mainTab.ext].subs.forEach(function(e) {
                res.add(new SubTab(e.name, true, e.id));
            });
        }else{
            res.add(new SubTab('全部', true, MainUtil.cates[mainTab.ext].id));
        }
    }
    return res;
}

function PageComponent_getContent(mainTab, subTab, key) {
    var res = new ArrayList();
    var nextKey = null;

    var path = "?ac=detail&sort_direction=desc"
    if (subTab && subTab.ext) {
        path += "&t="+subTab.ext;
    }
    if(key > 0){
        path += "&pg=" + key
    }

    var resp = MainUtil.getContent(path);
    if(resp && resp.list){
        resp.list.forEach(function(it) {
            res.add(makeCartoonCover({
                id: it.vod_id,
                url: it.vod_id,
                title: it.vod_name,
                cover: it.vod_pic,
                intro: Jsoup.parse(it.vod_remarks).text()
            }));
        });

        var curPage = resp.page ? resp.page: 0
        var pageCount = resp.pagecount ? resp.pagecount: 0
        if (curPage < pageCount){
            nextKey = new java.lang.Integer(parseInt(curPage) + 1);
        }
    }

    return new Pair(nextKey, res);
}

// Hook DetailedComponent ========================================

 function DetailedComponent_getDetailed(summary) {
    var resp = MainUtil.getContent("?ac=detail&ids=" + summary.id);
    if(resp.list && resp.list.length > 0){
        var it = resp.list[0];

        var status = Cartoon.STATUS_UNKNOWN;
        var updateStrategy = Cartoon.UPDATE_STRATEGY_ALWAYS;
        var genreList = new ArrayList();//标签
        if(it.vod_area){
            genreList.add(it.vod_area);
        }
        if(it.vod_lang){
            genreList.add(it.vod_lang);
        }

        var cartoon = makeCartoon(
            {
                id: summary.id,
                url: 'detail/'+summary.id,
                title: it.vod_name,
                genreList: genreList,
                cover: it.vod_pic,
                intro: Jsoup.parse(it.vod_blurb).text(),
                description: Jsoup.parse(it.vod_content).text(),
                updateStrategy: updateStrategy,
                isUpdate: false,
                status: status,
            }
        )

        var playLineList = new ArrayList();
        var playlistNames = it.vod_play_from ? it.vod_play_from.split("$$$") : [];
        if(playlistNames.length < 2){
            playlistNames = ["播放列表"];
        }

        it.vod_play_url
            .split("$$$")
            .forEach(function(list, ind) {
                if (ind >= playlistNames.length) {
                    return
                }

                var playLine = new PlayLine('p'+ind, playlistNames[ind], new ArrayList());
                list.split("#").forEach(function(part, order) {
                    var names = part.split('$');
                    playLine.episode.add(new Episode(names[1], names[0], order));
                });
                
                playLineList.add(playLine);
            });

        return new Pair(cartoon, playLineList);
    }
    return new Pair(null, null);
 }

 // Hook SearchComponent ========================================
 function SearchComponent_search(page, keyword) {
    var path = "?ac=detail&sort_direction=desc&wd="+keyword;
    if(page > 0){
        path += "&pg=" + page
    }

    var nextPage = null;
    var res = new ArrayList();
    var resp = MainUtil.getContent(path);

    if(resp.list){
        resp.list.forEach(function(it) {
            res.add(makeCartoonCover({
                id: it.vod_id,
                url: it.vod_id,
                title: it.vod_name,
                cover: it.vod_pic,
                intro: Jsoup.parse(it.vod_remarks).text()
            }));
        });

        var curPage = resp.page ? resp.page: 0
        var pageCount = resp.pagecount ? resp.pagecount: 0
        if (curPage < pageCount){
            nextPage = new java.lang.Integer(parseInt(curPage) + 1);
        }
    }

    return new Pair(nextPage, res);
 }

 // Hook PlayComponent ========================================
function PlayComponent_getPlayInfo(summary, playLine, episode) {
    var uri = new String(episode.id);
    
    var type = PlayerInfo.DECODE_TYPE_HLS;
    if (uri.indexOf(".mp4") > 0) {
        type = PlayerInfo.DECODE_TYPE_OTHER;
    }else{
        uri = MainUtil.removeAd(uri);
    }

    return new PlayerInfo(type, uri);
}


// main
function MainClass(){
    this._baseUrl = "https://cjhwba.com/api.php/provide/vod/?ac=list";
    this._adUrl = "";
    this._adUa = "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
    this.cates = {};
}

MainClass.prototype.getBaseUrl = function() {
    var baseUrl = new String(preferenceHelper.get("BaseUrl", this._baseUrl));
    var index = baseUrl.indexOf('?');
    if(index > 0){
        baseUrl = baseUrl.substring(0, index);
    }
    var lastChr = baseUrl.substring(baseUrl.length - 1, baseUrl.length);
    if(lastChr != '/'){
        baseUrl += '/';
    }
    return baseUrl;
};

MainClass.prototype.removeAd = function(url) {
    var adUrl = new String(preferenceHelper.get("AdUrl", this._adUrl));
    if(adUrl){
        if(adUrl.indexOf('?url=') > 0){
            var resp = this.getContent(adUrl+url, this._adUa);
            if(resp && resp.url){
                url = resp.url;
            }
        }
    }
    return url;
};

MainClass.prototype.getVideoCategories = function() {
    var resp = this.getContent("?ac=list");
    var arr = [];
    if(resp){
        var _t = this;
        if(resp.class){
            resp.class.forEach(function(e) {
                if(e.type_pid == 0){
                    arr.push({
                        id: 'c'+e.type_id,
                        name: e.type_name,
                    });
                    
                    if(!_t.cates['c'+e.type_id]){
                        _t.cates['c'+e.type_id] = {
                            id: e.type_id,
                            name: e.type_name,
                            subs: []
                        }
                    }else{
                        _t.cates['c'+e.type_id].name = e.type_name;
                    }
                }else{
                    if(!_t.cates['c'+e.type_pid]){
                        _t.cates['c'+e.type_pid] = {
                            id: e.type_pid,
                            name: '',
                            subs: []
                        }
                    }
                    _t.cates['c'+e.type_pid].subs.push({
                        id: e.type_id,
                        name: e.type_name,
                    });
                }
            });
        }
    }
    return arr;
};

MainClass.prototype.getContent = function(path, ua) {
    var url = (path.substring(0, 4) == 'http' ? path : this.getBaseUrl() + path);
    if(!ua){
        ua = networkHelper.randomUA;
    }

    var ret = new String(Jsoup.connect(url).userAgent(ua).ignoreContentType(true).execute().body());

    if(ret.substring(0, 1) == '{'){
        return JSON.parse(ret);
    }

    return null;
};

var MainUtil = new MainClass();
