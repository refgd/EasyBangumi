(function () {
    "use strict";

    var locationUrl = new URL(location.href);
    var host = locationUrl.hostname;
    var port = locationUrl.port;
    var httpBase = locationUrl.protocol + "//" + host + (port ? ":" + port : "");
    var socket = null;
    var editor = null;
    var busy = false;
    var selectionEvents = {};
    var currentSelection = null;
    var pageHistory = [];
    var requestSequence = 0;
    var installPending = false;
    var localIconData = "";
    var stageOrder = ["main", "sub", "content", "search", "playLine", "episode"];
    var stageLabels = {
        main: "主分类",
        sub: "次分类",
        content: "数据",
        search: "搜索结果",
        playLine: "播放线路",
        episode: "剧集"
    };

    function setStatus(text, state) {
        $("#status-text").text(text);
        $("#status-dot").attr("class", state || "");
    }

    function setBusy(value, text) {
        busy = value;
        $("#busy-indicator").toggleClass("visible", value).text(text || "处理中");
        $("#options button, #pager button, #page-key").prop("disabled", value);
    }

    function resetSession() {
        selectionEvents = {};
        currentSelection = null;
        pageHistory = [];
        $("#steps, #context").empty();
        $("#selection-title").text("正在连接");
        $("#selection-count").text("");
        $("#options").addClass("empty").text("等待插件返回主分类");
        $("#result").addClass("empty").text("尚无详情或播放结果");
        $("#pager").removeClass("visible");
        $("#log").text("");
    }

    function appendLog(message, state) {
        var log = $("#log");
        var line = $("<div>").text(message || "");
        if (String(state) === "-1") line.addClass("error");
        if (String(state) === "1000") line.addClass("success");
        log.append(line);
        log.scrollTop(log.get(0).scrollHeight);
    }

    function send(payload) {
        if (!socket || socket.readyState !== WebSocket.OPEN) {
            setStatus("连接已断开", "error");
            appendLog("无法发送命令：调试连接已断开", -1);
            return false;
        }
        if (!payload.requestId) payload.requestId = "web-" + (++requestSequence);
        socket.send(JSON.stringify(payload));
        return true;
    }

    function connect(initialPayload) {
        if (socket) socket.close();
        resetSession();
        setStatus("正在连接", "busy");
        setBusy(true, "连接中");

        var protocol = locationUrl.protocol === "https:" ? "wss://" : "ws://";
        socket = new WebSocket(protocol + host + ":" + (Number(port) + 1) + "/sourceDebug");
        socket.onopen = function () {
            setStatus("已连接", "online");
            send(initialPayload || { tag: "debug", key: editor.getValue() });
        };
        socket.onmessage = function (message) {
            handleMessage(message.data);
        };
        socket.onerror = function () {
            if (installPending) {
                installPending = false;
                showLoading(false);
            }
            setStatus("连接错误", "error");
            setBusy(false);
        };
        socket.onclose = function () {
            socket = null;
            if (installPending) {
                installPending = false;
                showLoading(false);
            }
            setStatus("已断开", "");
            setBusy(false);
        };
    }

    function handleMessage(raw) {
        var event;
        try {
            event = JSON.parse(raw);
        } catch (_) {
            appendLog(raw, 1);
            return;
        }

        if (event.type === "log") {
            appendLog(event.message, event.fields && event.fields.state);
        } else if (event.type === "busy") {
            setBusy(true, event.title);
        } else if (event.type === "selection") {
            setBusy(false);
            storeSelection(event);
            renderSelection(event);
        } else if (event.type === "context") {
            renderContext(event.fields || {});
        } else if (event.type === "result") {
            setBusy(false);
            renderResult(event);
        } else if (event.type === "ready") {
            setBusy(false);
            setStatus(event.title || "已就绪", "online");
        } else if (event.type === "hello" || event.type === "capabilities") {
            if (event.protocolVersion) setStatus("已连接 · 协议 v" + event.protocolVersion, "online");
        } else if (event.type === "capture") {
            appendLog("[原文] " + (event.title || "调试原文") + " (" + ((event.fields || {}).length || 0) + " 字符)\n" + (event.message || ""), 1);
        } else if (event.type === "installed") {
            installPending = false;
            showLoading(false);
            setBusy(false);
            setStatus(event.title || "插件已添加", "online");
            appendLog((event.title || "插件已添加") + "：" + (event.message || ""), 1000);
        } else if (event.type === "error") {
            if (installPending) {
                installPending = false;
                showLoading(false);
            }
            setBusy(false);
            setStatus("调试出错", "error");
            renderError(event);
        }
    }

    function storeSelection(event) {
        var stageIndex = stageOrder.indexOf(event.stage);
        if (stageIndex >= 0) {
            stageOrder.slice(stageIndex + 1).forEach(function (stage) {
                delete selectionEvents[stage];
            });
        }
        selectionEvents[event.stage] = event;
        renderSteps();
    }

    function renderSteps() {
        var steps = $("#steps").empty();
        stageOrder.forEach(function (stage) {
            var cached = selectionEvents[stage];
            if (!cached) return;
            var button = $("<button type='button'>").text(stageLabels[stage]);
            if (currentSelection && currentSelection.stage === stage) button.addClass("active");
            button.on("click", function () { renderSelection(cached); });
            steps.append(button);
        });
    }

    function renderSelection(event) {
        currentSelection = event;
        renderSteps();
        $("#selection-title").text(event.title || stageLabels[event.stage] || "选择");
        $("#selection-count").text((event.options || []).length + " 项");

        var options = $("#options").empty().removeClass("empty");
        if (!event.options || event.options.length === 0) {
            options.addClass("empty").text("该步骤没有返回数据");
        } else {
            event.options.forEach(function (option) {
                var button = $("<button type='button' class='option'>");
                if (option.image) {
                    $("<img loading='lazy' alt=''>")
                        .attr("src", option.image)
                        .on("error", function () { $(this).addClass("failed"); })
                        .appendTo(button);
                }
                var body = $("<span class='option-body'>").appendTo(button);
                $("<strong>").text(option.label || "未命名").appendTo(body);
                if (option.detail) $("<small>").text(option.detail).appendTo(body);
                $("<span class='option-index'>").text("#" + option.index).appendTo(button);
                button.on("click", function () {
                    if (busy) return;
                    if (event.stage === "main" || event.stage === "sub") pageHistory = [];
                    if (send({
                        tag: "select",
                        stage: event.stage,
                        index: String(option.index),
                        id: option.id || "",
                        label: option.label || ""
                    })) {
                        setBusy(true, "正在执行");
                    }
                });
                options.append(button);
            });
        }

        renderPager(event);
    }

    function renderPager(event) {
        var pager = $("#pager");
        if (event.stage !== "content" && event.stage !== "search") {
            pager.removeClass("visible");
            return;
        }
        pager.addClass("visible");
        $("#page-key").val(event.pageKey == null ? 0 : event.pageKey);
        $("#page-back").prop("disabled", busy || pageHistory.length === 0);
        $("#page-next").prop("disabled", busy || event.nextPageKey == null);
        $("#page-next").data("key", event.nextPageKey);
    }

    function requestPage(key, rememberCurrent) {
        var pageKey = Number(key);
        if (!Number.isInteger(pageKey)) {
            appendLog("分页参数必须是整数", -1);
            return;
        }
        if (rememberCurrent && currentSelection && currentSelection.pageKey != null) {
            pageHistory.push(currentSelection.pageKey);
        }
        if (send({ tag: "page", key: String(pageKey) })) setBusy(true, "正在加载页面");
    }

    function renderContext(fields) {
        var context = $("#context").empty();
        Object.keys(fields).forEach(function (key) {
            var item = $("<span>");
            $("<b>").text(key + "：").appendTo(item);
            item.append(document.createTextNode(fields[key]));
            context.append(item);
        });
    }

    function renderResult(event) {
        var result = $("#result").empty().removeClass("empty error");
        $("<h3>").text(event.stage === "playInfo" ? "播放结果" : "详情结果").appendTo(result);
        if (event.title) $("<p class='result-title'>").text(event.title).appendTo(result);
        var table = $("<dl>").appendTo(result);
        Object.keys(event.fields || {}).forEach(function (key) {
            $("<dt>").text(key).appendTo(table);
            $("<dd>").text(event.fields[key] || "-").appendTo(table);
        });
    }

    function renderError(event) {
        var result = $("#result").empty().removeClass("empty").addClass("error");
        $("<h3>").text(event.title || "调试失败").appendTo(result);
        $("<pre>").text(event.message || "未知错误").appendTo(result);
    }

    function downloadPlugin() {
        showLoading(true);
        var form = new FormData();
        form.append("code", Base64.encode(editor.getValue()));
        $.ajax({
            url: httpBase + "/api/downCode",
            data: form,
            cache: false,
            contentType: false,
            processData: false,
            method: "POST",
            dataType: "json",
            success: function (response) {
                showLoading(false);
                if (!response.isSuccess) {
                    alert(response.errorMsg);
                    return;
                }
                var bytes = Base64.toUint8Array(response.data);
                var blob = new Blob([bytes], { type: "application/octet-stream" });
                var link = document.createElement("a");
                link.download = "ext.ebg.jsc";
                link.href = URL.createObjectURL(blob);
                link.click();
                setTimeout(function () { URL.revokeObjectURL(link.href); }, 1500);
            },
            error: function () {
                showLoading(false);
                alert("下载插件失败");
            }
        });
    }

    function installPlugin() {
        var source = editor.getValue();
        if (!source.trim()) {
            appendLog("插件源码不能为空", -1);
            return;
        }
        installPending = true;
        showLoading(true);
        setStatus("正在添加插件", "busy");
        var command = { tag: "install", key: source, icon: localIconData };
        if (socket && socket.readyState === WebSocket.OPEN) {
            send(command);
        } else {
            connect(command);
        }
    }

    function showLoading(show) {
        $("body").toggleClass("loading", show);
    }

    $(function () {
        editor = CodeMirror.fromTextArea($("#source-editor").get(0), {
            lineNumbers: true,
            mode: "javascript",
            theme: "material-darker"
        });

        $.ajax({
            url: "../assets/sample.js",
            method: "GET",
            dataType: "text",
            success: function (source) { editor.setValue(source); }
        });

        $("[data-act='test']").on("click", function () { connect(); });
        $("[data-act='icon']").on("click", function () { $("#plugin-icon").trigger("click"); });
        $("#plugin-icon").on("change", function () {
            var file = this.files && this.files[0];
            if (!file) return;
            if (!file.type || file.type.indexOf("image/") !== 0) {
                appendLog("请选择图片文件", -1);
                return;
            }
            if (file.size > 4 * 1024 * 1024) {
                appendLog("图标不能超过 4 MiB", -1);
                return;
            }
            var reader = new FileReader();
            reader.onload = function () {
                localIconData = String(reader.result || "");
                appendLog("已选择本地图标：" + file.name, 1000);
                $("[data-act='icon']").text("已选图标");
            };
            reader.onerror = function () { appendLog("读取图标失败", -1); };
            reader.readAsDataURL(file);
        });
        $("[data-act='install']").on("click", installPlugin);
        $("[data-act='download']").on("click", downloadPlugin);
        $("#clear-log").on("click", function () { $("#log").empty(); });
        $("#page-next").on("click", function () { requestPage($(this).data("key"), true); });
        $("#page-back").on("click", function () {
            if (pageHistory.length) requestPage(pageHistory.pop(), false);
        });
        $("#page-go").on("click", function () { requestPage($("#page-key").val(), true); });
        $("#page-key").on("keydown", function (event) {
            if (event.key === "Enter") requestPage($(this).val(), true);
        });
        $("#search-form").on("submit", function (event) {
            event.preventDefault();
            var keyword = $("#search-keyword").val().trim();
            if (!keyword) {
                appendLog("请输入搜索关键词", -1);
                return;
            }
            pageHistory = [];
            if (send({ tag: "search", keyword: keyword, page: "0" })) setBusy(true, "正在搜索");
        });
    });
})();
