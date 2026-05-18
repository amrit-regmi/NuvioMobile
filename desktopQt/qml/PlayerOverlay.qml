import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Item {
    id: root
    anchors.fill: parent

    property bool modalOpen: audioModal.visible || subtitleModal.visible || sourceModal.visible || episodeModal.visible || submitIntroModal.visible || playerOverlay.playerErrorVisible
    property int safeMargin: Math.max(24, Math.round(width * 0.035))
    property int controlSize: width < 720 ? 54 : 68
    property bool pauseMetadataReady: false
    property bool lockedOverlayVisible: false
    readonly property string iconClose: "qrc:/qml/icons/ic_close.svg"
    readonly property string iconBack: "qrc:/qml/icons/ic_arrow_back.svg"
    readonly property string iconBrightness: "qrc:/qml/icons/ic_brightness.svg"
    readonly property string iconCloudDownload: "qrc:/qml/icons/ic_cloud_download.svg"
    readonly property string iconFastForward: "qrc:/qml/icons/ic_fast_forward.svg"
    readonly property string iconFastRewind: "qrc:/qml/icons/ic_fast_rewind.svg"
    readonly property string iconFlag: "qrc:/qml/icons/ic_flag.svg"
    readonly property string iconForward10: "qrc:/qml/icons/ic_forward_10.svg"
    readonly property string iconLock: "qrc:/qml/icons/ic_lock.svg"
    readonly property string iconLockOpen: "qrc:/qml/icons/ic_lock_open.svg"
    readonly property string iconPause: "qrc:/qml/icons/ic_player_pause.svg"
    readonly property string iconPlay: "qrc:/qml/icons/ic_player_play.svg"
    readonly property string iconReplay10: "qrc:/qml/icons/ic_replay_10.svg"
    readonly property string iconRefresh: "qrc:/qml/icons/ic_refresh.svg"
    readonly property string iconResize: "qrc:/qml/icons/ic_player_aspect_ratio.svg"
    readonly property string iconSkipNext: "qrc:/qml/icons/ic_skip_next.svg"
    readonly property string iconAudio: "qrc:/qml/icons/ic_player_audio_filled.svg"
    readonly property string iconSubtitles: "qrc:/qml/icons/ic_player_subtitles.svg"
    readonly property string iconSpeed: "qrc:/qml/icons/ic_speed.svg"
    readonly property string iconSources: "qrc:/qml/icons/ic_swap_horiz.svg"
    readonly property string iconEpisodes: "qrc:/qml/icons/ic_video_library.svg"
    readonly property string iconVolume: "qrc:/qml/icons/ic_volume_up.svg"
    readonly property string iconVolumeMuted: "qrc:/qml/icons/ic_volume_off.svg"

    function formatTime(ms) {
        var total = Math.max(0, Math.floor(ms / 1000));
        var hours = Math.floor(total / 3600);
        var minutes = Math.floor((total % 3600) / 60);
        var seconds = total % 60;
        function pad(value) {
            return value < 10 ? "0" + value : "" + value;
        }
        return hours > 0 ? hours + ":" + pad(minutes) + ":" + pad(seconds) : minutes + ":" + pad(seconds);
    }

    function languageName(code) {
        var normalized = String(code || "").trim().toLowerCase();
        if (normalized.length === 0 || normalized === "und" || normalized === "unknown")
            return "Unknown";
        var labels = {
            "ar": "Arabic",
            "en": "English",
            "eng": "English",
            "es": "Spanish",
            "fr": "French",
            "de": "German",
            "hi": "Hindi",
            "it": "Italian",
            "ja": "Japanese",
            "ko": "Korean",
            "pt": "Portuguese",
            "ru": "Russian",
            "zh": "Chinese"
        };
        return labels[normalized] || code.toUpperCase();
    }

    function formatSubmitTime(ms) {
        var total = Math.max(0, Math.floor(ms / 1000));
        var minutes = Math.floor(total / 60);
        var seconds = total % 60;
        return (minutes < 10 ? "0" + minutes : "" + minutes) + ":" + (seconds < 10 ? "0" + seconds : "" + seconds);
    }

    function pauseMetadataEligible() {
        return !root.modalOpen && !playerOverlay.controlsVisible && !playerOverlay.locked && !playerOverlay.playing && !playerOverlay.loading && playerOverlay.durationMs > 0;
    }

    function updatePauseMetadataDelay() {
        if (pauseMetadataEligible()) {
            if (!pauseMetadataReady && !pauseMetadataTimer.running)
                pauseMetadataTimer.restart();
        } else {
            pauseMetadataTimer.stop();
            pauseMetadataReady = false;
        }
    }

    function revealLockedOverlay() {
        if (!playerOverlay.locked)
            return;
        lockedOverlayVisible = true;
        lockedOverlayTimer.restart();
    }

    onModalOpenChanged: updatePauseMetadataDelay()
    Component.onCompleted: updatePauseMetadataDelay()

    Timer {
        id: pauseMetadataTimer
        interval: 5000
        repeat: false
        onTriggered: {
            if (root.pauseMetadataEligible())
                root.pauseMetadataReady = true;
        }
    }

    Timer {
        id: lockedOverlayTimer
        interval: 2000
        repeat: false
        onTriggered: root.lockedOverlayVisible = false
    }

    Connections {
        target: playerOverlay
        function onPlaybackChanged() { root.updatePauseMetadataDelay(); }
        function onControlsVisibleChanged() { root.updatePauseMetadataDelay(); }
        function onLockedChanged() {
            root.updatePauseMetadataDelay();
            root.lockedOverlayVisible = false;
            lockedOverlayTimer.stop();
        }
    }

    MouseArea {
        id: gestureTouch
        anchors.fill: parent
        enabled: !root.modalOpen
        preventStealing: true
        pressAndHoldInterval: 420
        property real pressX: 0
        property real pressY: 0
        property real baselinePositionMs: 0
        property real horizontalPreviewMs: 0
        property real initialVolumeFraction: 1
        property real initialBrightnessFraction: 0.5
        property string gestureMode: ""
        property bool suppressClick: false
        property bool speedBoostActive: false

        function resetGestureState() {
            gestureMode = "";
            pressX = 0;
            pressY = 0;
            baselinePositionMs = 0;
            horizontalPreviewMs = 0;
            speedBoostActive = false;
        }

        onPressed: function (mouse) {
            pressX = mouse.x;
            pressY = mouse.y;
            baselinePositionMs = Math.max(0, playerOverlay.positionMs);
            horizontalPreviewMs = baselinePositionMs;
            initialVolumeFraction = playerOverlay.volumeFraction;
            initialBrightnessFraction = playerOverlay.brightnessFraction;
            gestureMode = "";
            suppressClick = false;
            speedBoostActive = false;
        }
        onPositionChanged: function (mouse) {
            if (playerOverlay.locked || speedBoostActive)
                return;

            var dx = mouse.x - pressX;
            var dy = mouse.y - pressY;
            var absDx = Math.abs(dx);
            var absDy = Math.abs(dy);
            var slop = 12;

            if (gestureMode.length === 0) {
                if (absDx <= slop && absDy <= slop)
                    return;
                if (absDx > absDy) {
                    gestureMode = "seek";
                } else if (pressX < root.width * 0.4) {
                    gestureMode = "brightness";
                } else if (pressX > root.width * 0.6) {
                    gestureMode = "volume";
                } else {
                    return;
                }
                suppressClick = true;
                playerOverlay.clearGestureFeedbackNow();
            }

            if (gestureMode === "seek") {
                var duration = Math.max(0, playerOverlay.durationMs);
                var sensitivitySeconds = duration >= 3600000 ? 120 : (duration >= 1800000 ? 90 : 60);
                var target = baselinePositionMs + ((dx / Math.max(1, root.width)) * sensitivitySeconds * 1000);
                horizontalPreviewMs = duration > 0 ? Math.max(0, Math.min(duration, target)) : Math.max(0, target);
                playerOverlay.previewHorizontalSeek(horizontalPreviewMs, baselinePositionMs);
            } else if (gestureMode === "brightness") {
                playerOverlay.setGestureBrightness(initialBrightnessFraction + (-dy / Math.max(1, root.height)));
            } else if (gestureMode === "volume") {
                playerOverlay.setGestureVolume(initialVolumeFraction + (-dy / Math.max(1, root.height)));
            }
        }
        onReleased: function () {
            if (speedBoostActive) {
                playerOverlay.endHoldToSpeed();
                suppressClick = true;
            } else if (gestureMode === "seek") {
                playerOverlay.commitHorizontalSeek(horizontalPreviewMs);
            }
            resetGestureState();
        }
        onCanceled: {
            if (speedBoostActive)
                playerOverlay.endHoldToSpeed();
            if (gestureMode.length > 0)
                playerOverlay.clearGestureFeedbackNow();
            resetGestureState();
        }
        onPressAndHold: function () {
            if (playerOverlay.locked) {
                root.revealLockedOverlay();
                return;
            }
            speedBoostActive = playerOverlay.beginHoldToSpeed();
            if (speedBoostActive)
                suppressClick = true;
        }
        onClicked: {
            if (playerOverlay.locked) {
                root.revealLockedOverlay();
                return;
            }
            if (suppressClick) {
                suppressClick = false;
                return;
            }
            playerOverlay.toggleControls();
        }
        onDoubleClicked: function (mouse) {
            if (playerOverlay.locked) {
                root.revealLockedOverlay();
                return;
            }
            if (suppressClick) {
                suppressClick = false;
                return;
            }
            if (mouse.x < root.width * 0.4) {
                playerOverlay.handleDoubleTapSeek(false);
            } else if (mouse.x > root.width * 0.6) {
                playerOverlay.handleDoubleTapSeek(true);
            } else {
                playerOverlay.toggleControls();
            }
        }
    }

    OpeningOverlay {
        anchors.fill: parent
        active: playerOverlay.openingOverlayVisible
        z: 5
    }

    PauseMetadataOverlay {
        anchors.fill: parent
        active: root.pauseMetadataReady && root.pauseMetadataEligible()
    }

    GestureFeedbackPill {
        anchors.top: parent.top
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.topMargin: Math.max(26, root.safeMargin)
        active: playerOverlay.gestureFeedbackVisible && !root.modalOpen && !playerOverlay.locked
        icon: playerOverlay.gestureFeedbackIcon
        message: playerOverlay.gestureFeedbackMessage
        secondaryMessage: playerOverlay.gestureFeedbackSecondaryMessage
        danger: playerOverlay.gestureFeedbackDanger
        z: 6
    }

    ErrorOverlay {
        anchors.fill: parent
        active: playerOverlay.playerErrorVisible
        message: playerOverlay.playerErrorMessage
        z: 12
    }

    Rectangle {
        anchors.fill: parent
        color: "transparent"
        opacity: playerOverlay.controlsVisible && !playerOverlay.locked ? 1 : 0
        visible: opacity > 0.01
        Behavior on opacity {
            NumberAnimation {
                duration: 180
                easing.type: Easing.OutCubic
            }
        }

        Rectangle {
            anchors.top: parent.top
            anchors.left: parent.left
            anchors.right: parent.right
            height: 170
            gradient: Gradient {
                GradientStop {
                    position: 0.0
                    color: "#b0000000"
                }
                GradientStop {
                    position: 1.0
                    color: "#00000000"
                }
            }
        }

        Rectangle {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            height: 250
            gradient: Gradient {
                GradientStop {
                    position: 0.0
                    color: "#00000000"
                }
                GradientStop {
                    position: 1.0
                    color: "#b0000000"
                }
            }
        }

        RowLayout {
            anchors.top: parent.top
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.margins: root.safeMargin
            spacing: 14

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 5
                opacity: playerOverlay.parentalGuideVisible ? 0 : 1
                Behavior on opacity {
                    NumberAnimation {
                        duration: 180
                    }
                }

                Text {
                    Layout.fillWidth: true
                    text: playerOverlay.title
                    color: "white"
                    font.pixelSize: root.width < 720 ? 24 : 32
                    font.bold: true
                    elide: Text.ElideRight
                    maximumLineCount: 2
                    wrapMode: Text.WordWrap
                }

                Text {
                    Layout.fillWidth: true
                    visible: playerOverlay.episodeLabel.length > 0
                    text: playerOverlay.episodeLabel
                    color: "#e6ffffff"
                    font.pixelSize: 15
                    elide: Text.ElideRight
                }

                RowLayout {
                    spacing: 8
                    Text {
                        text: playerOverlay.streamTitle
                        color: "#b8ffffff"
                        font.pixelSize: 13
                        elide: Text.ElideRight
                        Layout.maximumWidth: 360
                        visible: playerOverlay.streamTitle.length > 0
                    }
                    Text {
                        text: playerOverlay.providerName
                        color: "#b8ffffff"
                        font.pixelSize: 13
                        font.italic: true
                        elide: Text.ElideRight
                        Layout.maximumWidth: 220
                        visible: playerOverlay.providerName.length > 0
                    }
                }
            }

            HeaderButton {
                visible: playerOverlay.submitIntroAvailable
                label: "Submit intro"
                iconSource: root.iconFlag
                onTriggered: playerOverlay.openSubmitIntroDialog()
            }
            HeaderButton {
                label: playerOverlay.locked ? "Unlock" : "Lock"
                iconSource: playerOverlay.locked ? root.iconLockOpen : root.iconLock
                onTriggered: playerOverlay.toggleLock()
            }
            HeaderButton {
                label: "Close"
                iconSource: root.iconClose
                onTriggered: playerOverlay.closePlayer()
            }
        }

        ParentalGuideOverlay {
            anchors.top: parent.top
            anchors.left: parent.left
            anchors.topMargin: root.safeMargin
            anchors.leftMargin: root.safeMargin
            warnings: playerOverlay.parentalWarnings
            active: playerOverlay.parentalGuideVisible
        }

        Row {
            anchors.centerIn: parent
            spacing: root.width < 720 ? 28 : 44

            CenterButton {
                size: root.controlSize
                iconSource: root.iconReplay10
                label: "Seek back 10 seconds"
                onTriggered: playerOverlay.seekBy(-10000)
            }
            CenterButton {
                size: root.controlSize + 18
                busy: playerOverlay.loading
                iconSource: playerOverlay.loading ? "" : (playerOverlay.playing ? root.iconPause : root.iconPlay)
                iconScale: 0.52
                label: playerOverlay.playing ? "Pause" : "Play"
                onTriggered: playerOverlay.togglePlayback()
            }
            CenterButton {
                size: root.controlSize
                iconSource: root.iconForward10
                label: "Seek forward 10 seconds"
                onTriggered: playerOverlay.seekBy(10000)
            }
        }

        ColumnLayout {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            anchors.leftMargin: root.safeMargin + 20
            anchors.rightMargin: root.safeMargin + 20
            anchors.bottomMargin: Math.max(24, root.safeMargin - 4)
            spacing: 6

            Slider {
                id: progressSlider
                Layout.fillWidth: true
                from: 0
                to: Math.max(1, playerOverlay.durationMs)
                value: Math.min(playerOverlay.positionMs, to)
                onMoved: playerOverlay.seekTo(value)
            }

            RowLayout {
                Layout.fillWidth: true
                TimePill {
                    text: root.formatTime(playerOverlay.positionMs)
                }
                Item {
                    Layout.fillWidth: true
                }
                TimePill {
                    text: root.formatTime(playerOverlay.durationMs)
                }
            }

            Rectangle {
                Layout.alignment: Qt.AlignHCenter
                radius: 24
                color: "#80000000"
                border.color: "#33ffffff"
                border.width: 1
                implicitHeight: 50
                implicitWidth: actionRow.implicitWidth + 10

                Row {
                    id: actionRow
                    anchors.centerIn: parent
                    spacing: 0
                    PillButton {
                        iconSource: root.iconResize
                        label: playerOverlay.resizeModeLabel
                        onTriggered: playerOverlay.cycleResizeMode()
                    }
                    PillButton {
                        iconSource: root.iconSpeed
                        label: playerOverlay.speedLabel
                        onTriggered: playerOverlay.cyclePlaybackSpeed()
                    }
                    PillButton {
                        iconSource: root.iconSubtitles
                        label: "Subs"
                        onTriggered: {
                            playerOverlay.refreshTracks();
                            subtitleModal.open();
                        }
                    }
                    PillButton {
                        iconSource: root.iconAudio
                        label: "Audio"
                        onTriggered: {
                            playerOverlay.refreshTracks();
                            audioModal.open();
                        }
                    }
                    PillButton {
                        visible: playerOverlay.sourcePickerAvailable
                        iconSource: root.iconSources
                        label: "Sources"
                        onTriggered: {
                            playerOverlay.openSourcesPanel();
                            sourceModal.open();
                        }
                    }
                    PillButton {
                        visible: playerOverlay.episodePickerAvailable
                        iconSource: root.iconEpisodes
                        label: "Episodes"
                        onTriggered: {
                            playerOverlay.openEpisodesPanel();
                            episodeModal.open();
                        }
                    }
                }
            }
        }
    }

    SkipIntroPill {
        anchors.left: parent.left
        anchors.bottom: parent.bottom
        anchors.leftMargin: root.safeMargin + 20
        anchors.bottomMargin: 122
        active: playerOverlay.skipIntroAvailable && !root.modalOpen && !playerOverlay.locked
        dismissed: playerOverlay.skipIntroDismissed
        controlsVisible: playerOverlay.controlsVisible
        intervalKey: playerOverlay.skipIntroKey
        label: playerOverlay.skipIntroLabel
        onTriggered: playerOverlay.skipIntro()
    }

    Rectangle {
        id: nextEpisodeCard
        property bool active: playerOverlay.nextEpisodeAvailable && !root.modalOpen && !playerOverlay.locked
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.rightMargin: root.safeMargin + 20
        anchors.bottomMargin: 122
        visible: active || opacity > 0.01
        opacity: active ? 1 : 0
        radius: 16
        color: nextMouse.containsMouse ? "#ee232323" : "#e6191919"
        border.color: "#22ffffff"
        border.width: 1
        width: Math.min(292, Math.max(292, nextContent.implicitWidth + 18))
        height: playerOverlay.nextEpisodeStatusText.length > 0 ? 78 : 64
        transform: Translate {
            id: nextEpisodeSlide
            x: nextEpisodeCard.active ? 0 : nextEpisodeCard.width / 2
            Behavior on x {
                NumberAnimation {
                    duration: nextEpisodeCard.active ? 260 : 200
                    easing.type: Easing.OutCubic
                }
            }
        }

        Behavior on opacity {
            NumberAnimation {
                duration: nextEpisodeCard.active ? 220 : 160
                easing.type: Easing.OutCubic
            }
        }

        MouseArea {
            id: nextMouse
            anchors.fill: parent
            hoverEnabled: true
            enabled: nextEpisodeCard.active && playerOverlay.nextEpisodePlayable
            z: 0
            onClicked: playerOverlay.playNextEpisode()
        }

        RowLayout {
            id: nextContent
            z: 1
            anchors.fill: parent
            anchors.leftMargin: 9
            anchors.rightMargin: 10
            spacing: 8

            Rectangle {
                Layout.preferredWidth: 78
                Layout.preferredHeight: 44
                Layout.alignment: Qt.AlignVCenter
                radius: 9
                clip: true
                color: "#262626"
                Image {
                    anchors.fill: parent
                    visible: playerOverlay.nextEpisodeThumbnail.length > 0 && status === Image.Ready
                    source: playerOverlay.nextEpisodeThumbnail
                    asynchronous: true
                    fillMode: Image.PreserveAspectCrop
                }
                Rectangle {
                    anchors.fill: parent
                    gradient: Gradient {
                        GradientStop {
                            position: 0.0
                            color: "#00000000"
                        }
                        GradientStop {
                            position: 1.0
                            color: "#52000000"
                        }
                    }
                }
            }

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 2
                Text {
                    Layout.fillWidth: true
                    text: "Next episode"
                    color: "#ccffffff"
                    font.pixelSize: 11
                    font.bold: true
                }
                Text {
                    Layout.fillWidth: true
                    text: playerOverlay.nextEpisodeLabel
                    color: "white"
                    font.pixelSize: 13
                    font.bold: true
                    elide: Text.ElideRight
                }
                Text {
                    Layout.fillWidth: true
                    visible: playerOverlay.nextEpisodeStatusText.length > 0
                    text: playerOverlay.nextEpisodeStatusText
                    color: "#c7ffffff"
                    font.pixelSize: 10
                    elide: Text.ElideRight
                }
            }
            Rectangle {
                Layout.alignment: Qt.AlignVCenter
                Layout.preferredHeight: 28
                Layout.preferredWidth: playBadgeContent.implicitWidth + 18
                radius: 14
                color: "transparent"
                border.color: "#33ffffff"
                border.width: 1
                Row {
                    id: playBadgeContent
                    anchors.centerIn: parent
                    spacing: 4
                    BusyIndicator {
                        visible: playerOverlay.nextEpisodeAutoPlaySearching
                        running: visible
                        width: 14
                        height: 14
                    }
                    Item {
                        visible: !playerOverlay.nextEpisodeAutoPlaySearching
                        width: 13
                        height: 13
                        Image {
                            anchors.fill: parent
                            source: root.iconPlay
                            sourceSize.width: 13
                            sourceSize.height: 13
                            opacity: playerOverlay.nextEpisodePlayable ? 1 : 0.65
                        }
                    }
                    Text {
                        text: playerOverlay.nextEpisodePlayable ? "Play" : "Unaired"
                        color: playerOverlay.nextEpisodePlayable ? "white" : "#b8ffffff"
                        font.pixelSize: 11
                    }
                }
            }
            Text {
                text: "×"
                color: "#ccffffff"
                font.pixelSize: 18
                Layout.alignment: Qt.AlignVCenter
                MouseArea {
                    anchors.fill: parent
                    onClicked: function (mouse) {
                        mouse.accepted = true;
                        playerOverlay.dismissNextEpisode();
                    }
                }
            }
        }
    }

    Rectangle {
        anchors.fill: parent
        color: "transparent"
        visible: playerOverlay.locked && (root.lockedOverlayVisible || opacity > 0.01)
        opacity: root.lockedOverlayVisible ? 1 : 0

        Behavior on opacity {
            NumberAnimation {
                duration: root.lockedOverlayVisible ? 180 : 160
                easing.type: Easing.OutCubic
            }
        }

        Rectangle {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            height: 220
            gradient: Gradient {
                GradientStop {
                    position: 0.0
                    color: "#00000000"
                }
                GradientStop {
                    position: 1.0
                    color: "#bf000000"
                }
            }
        }

        Column {
            anchors.centerIn: parent
            spacing: 12
            HeaderButton {
                width: 78
                height: 78
                iconSource: root.iconLock
                label: "Unlock controls"
                onTriggered: playerOverlay.toggleLock()
            }
            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                text: "Tap to unlock"
                color: "#ebffffff"
                font.pixelSize: 16
                font.bold: true
            }
        }

        ColumnLayout {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            anchors.leftMargin: root.safeMargin + 20
            anchors.rightMargin: root.safeMargin + 20
            anchors.bottomMargin: Math.max(24, root.safeMargin - 4)
            Slider {
                Layout.fillWidth: true
                enabled: false
                from: 0
                to: Math.max(1, playerOverlay.durationMs)
                value: Math.min(playerOverlay.positionMs, to)
            }
            RowLayout {
                Layout.fillWidth: true
                TimePill {
                    text: root.formatTime(playerOverlay.positionMs)
                }
                Item {
                    Layout.fillWidth: true
                }
                TimePill {
                    text: root.formatTime(playerOverlay.durationMs)
                }
            }
        }
    }

    TrackModal {
        id: audioModal
        title: "Audio tracks"
        emptyText: "No audio tracks available"
        model: playerOverlay.audioTracks
        selectedIndex: playerOverlay.selectedAudioIndex
        closeDelayMs: 200
        onSelected: function (index) {
            playerOverlay.selectAudioTrack(index);
            closeAfterSelection();
        }
    }

    TrackModal {
        id: subtitleModal
        title: "Subtitles"
        emptyText: "No subtitles available"
        includeNone: true
        model: playerOverlay.subtitleTracks
        selectedIndex: playerOverlay.selectedSubtitleIndex
        addonMode: true
        addonModel: playerOverlay.addonSubtitles
        addonLoading: playerOverlay.addonSubtitlesLoading
        onSelected: function (index) {
            playerOverlay.selectSubtitleTrack(index);
        }
        onFetchAddons: playerOverlay.fetchAddonSubtitles()
        onAddonSelected: function (index) {
            playerOverlay.selectAddonSubtitle(index);
        }
    }

    SourceModal {
        id: sourceModal
    }

    EpisodeModal {
        id: episodeModal
    }

    SubmitIntroModal {
        id: submitIntroModal
        visible: playerOverlay.submitIntroVisible
        onClosed: playerOverlay.dismissSubmitIntroDialog()
    }

    component HeaderButton: Rectangle {
        id: button
        property string symbol: ""
        property string iconSource: ""
        property string label: ""
        signal triggered
        implicitWidth: 46
        implicitHeight: 46
        radius: width / 2
        color: mouse.containsMouse ? "#66000000" : "#59000000"
        border.color: "#22ffffff"
        border.width: 1

        PlayerIcon {
            anchors.centerIn: parent
            source: button.iconSource
            fallback: button.symbol
            size: Math.min(parent.width, parent.height) * 0.48
        }

        MouseArea {
            id: mouse
            anchors.fill: parent
            hoverEnabled: true
            onClicked: button.triggered()
        }

    }

    component CenterButton: Rectangle {
        id: button
        property int size: 68
        property string symbol: ""
        property string iconSource: ""
        property real iconScale: 0.58
        property bool busy: false
        property string label: ""
        signal triggered
        width: size
        height: size
        radius: size / 2
        color: mouse.containsMouse ? "#33ffffff" : "transparent"
        scale: mouse.pressed ? 0.94 : 1.0
        Behavior on scale {
            NumberAnimation {
                duration: 100
            }
        }

        PlayerIcon {
            anchors.centerIn: parent
            source: button.iconSource
            fallback: button.symbol
            size: button.size * button.iconScale
            busy: button.busy
        }

        MouseArea {
            id: mouse
            anchors.fill: parent
            hoverEnabled: true
            onClicked: button.triggered()
        }

    }

    component PillButton: Rectangle {
        id: button
        property string symbol: ""
        property string iconSource: ""
        property string label: ""
        signal triggered
        implicitWidth: content.implicitWidth + 24
        implicitHeight: 46
        radius: 23
        color: mouse.containsMouse ? "#26ffffff" : "transparent"

        Row {
            id: content
            anchors.centerIn: parent
            spacing: 8
            PlayerIcon {
                source: button.iconSource
                fallback: button.symbol
                size: 18
            }
            Text {
                text: button.label
                color: "white"
                font.pixelSize: 13
                font.bold: true
            }
        }

        MouseArea {
            id: mouse
            anchors.fill: parent
            hoverEnabled: true
            onClicked: button.triggered()
        }
    }

    component PanelChipButton: Rectangle {
        id: button
        property string iconSource: ""
        property string label: ""
        signal triggered
        implicitWidth: content.implicitWidth + 24
        implicitHeight: 32
        radius: 16
        color: chipMouse.containsMouse ? "#28ffffff" : "#18ffffff"
        border.color: "#2effffff"
        border.width: 1
        scale: chipMouse.pressed ? 0.96 : 1.0
        Behavior on scale {
            NumberAnimation {
                duration: 90
                easing.type: Easing.OutCubic
            }
        }

        Row {
            id: content
            anchors.centerIn: parent
            spacing: 5
            PlayerIcon {
                visible: button.iconSource.length > 0
                source: button.iconSource
                size: 14
            }
            Text {
                text: button.label
                color: "#d8ffffff"
                font.pixelSize: 12
                font.bold: false
            }
        }

        MouseArea {
            id: chipMouse
            anchors.fill: parent
            hoverEnabled: true
            onClicked: button.triggered()
        }
    }

    component PlayerIcon: Item {
        id: icon
        property string source: ""
        property string fallback: ""
        property real size: 18
        property bool busy: false
        width: size
        height: size

        Image {
            anchors.fill: parent
            visible: icon.source.length > 0 && !icon.busy
            source: icon.source
            sourceSize.width: icon.size
            sourceSize.height: icon.size
            fillMode: Image.PreserveAspectFit
            smooth: true
            mipmap: true
        }

        BusyIndicator {
            anchors.fill: parent
            visible: icon.busy
            running: visible
        }

        Text {
            anchors.centerIn: parent
            visible: icon.source.length === 0 && !icon.busy
            text: icon.fallback
            color: "white"
            font.pixelSize: icon.size * 0.82
            font.bold: true
        }
    }

    component TimePill: Rectangle {
        property alias text: label.text
        implicitWidth: label.implicitWidth + 20
        implicitHeight: 28
        radius: 14
        color: "#80000000"
        border.color: "#33ffffff"
        border.width: 1
        Text {
            id: label
            anchors.centerIn: parent
            color: "white"
            font.pixelSize: 12
            font.bold: true
        }
    }

    component SkipIntroPill: Rectangle {
        id: skipButton
        property bool active: false
        property bool dismissed: false
        property bool controlsVisible: false
        property string intervalKey: ""
        property string label: ""
        property real progress: 0
        property bool autoHidden: false
        property bool rendered: active && (!dismissed || controlsVisible) && (!autoHidden || controlsVisible)
        signal triggered

        function resetAutoHide() {
            progressAnimation.stop();
            progress = 0;
            autoHidden = false;
        }

        function updateAutoHide() {
            if (!active) {
                resetAutoHide();
                return;
            }
            if (!dismissed && !controlsVisible && !autoHidden) {
                if (!progressAnimation.running)
                    progressAnimation.restart();
            } else {
                progressAnimation.stop();
            }
        }

        visible: opacity > 0.01
        opacity: rendered ? 1 : 0
        scale: rendered ? 1 : 0.8
        radius: 16
        clip: true
        color: skipMouse.containsMouse ? "#ee232323" : "#d91e1e1e"
        width: skipContent.implicitWidth + 36
        height: 51

        onActiveChanged: updateAutoHide()
        onDismissedChanged: {
            if (!dismissed)
                resetAutoHide();
            updateAutoHide();
        }
        onControlsVisibleChanged: updateAutoHide()
        onIntervalKeyChanged: {
            resetAutoHide();
            updateAutoHide();
        }
        Component.onCompleted: updateAutoHide()

        Behavior on opacity {
            NumberAnimation {
                duration: skipButton.rendered ? 300 : 200
                easing.type: Easing.OutCubic
            }
        }
        Behavior on scale {
            NumberAnimation {
                duration: skipButton.rendered ? 300 : 200
                easing.type: Easing.OutCubic
            }
        }

        NumberAnimation {
            id: progressAnimation
            target: skipButton
            property: "progress"
            to: 1
            duration: Math.max(1, Math.round((1 - skipButton.progress) * 10000))
            easing.type: Easing.Linear
            onFinished: {
                if (skipButton.progress >= 1)
                    skipButton.autoHidden = true;
            }
        }

        Row {
            id: skipContent
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.top: parent.top
            anchors.topMargin: 12
            spacing: 8
            Image {
                anchors.verticalCenter: parent.verticalCenter
                width: 20
                height: 20
                source: root.iconSkipNext
                sourceSize.width: 20
                sourceSize.height: 20
            }
            Text {
                text: skipButton.label
                color: "white"
                font.pixelSize: 14
            }
        }

        Rectangle {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            height: 3
            color: (!skipButton.controlsVisible && !skipButton.autoHidden && !skipButton.dismissed) ? "#26ffffff" : "transparent"
            Rectangle {
                anchors.left: parent.left
                anchors.top: parent.top
                anchors.bottom: parent.bottom
                width: parent.width * skipButton.progress
                color: (!skipButton.controlsVisible && !skipButton.autoHidden && !skipButton.dismissed) ? "#d91e1e1e" : "transparent"
            }
        }

        MouseArea {
            id: skipMouse
            anchors.fill: parent
            hoverEnabled: true
            onClicked: skipButton.triggered()
        }
    }

    component GestureFeedbackPill: Rectangle {
        id: feedback
        property bool active: false
        property string icon: "speed"
        property string message: ""
        property string secondaryMessage: ""
        property bool danger: false

        function iconSource() {
            switch (icon) {
            case "seekForward":
                return root.iconFastForward;
            case "seekBackward":
                return root.iconFastRewind;
            case "resize":
                return root.iconResize;
            case "brightness":
                return root.iconBrightness;
            case "volume":
                return root.iconVolume;
            case "volumeMuted":
                return root.iconVolumeMuted;
            case "speed":
                return root.iconSpeed;
            default:
                return root.iconSpeed;
            }
        }

        visible: opacity > 0.01
        opacity: active ? 1 : 0
        scale: active ? 1 : 0.96
        radius: 24
        color: danger ? "#e05d1f1f" : "#bf000000"
        border.color: danger ? "#55ffc1c1" : "#24ffffff"
        border.width: 1
        width: feedbackContent.implicitWidth + 24
        height: 48

        Behavior on opacity {
            NumberAnimation {
                duration: feedback.active ? 130 : 180
                easing.type: Easing.OutCubic
            }
        }
        Behavior on scale {
            NumberAnimation {
                duration: 160
                easing.type: Easing.OutCubic
            }
        }

        Row {
            id: feedbackContent
            anchors.centerIn: parent
            spacing: 8

            Rectangle {
                width: 30
                height: 30
                radius: 15
                color: feedback.danger ? "#38ff8a80" : "#26ffffff"
                Image {
                    anchors.centerIn: parent
                    width: 17
                    height: 17
                    source: feedback.iconSource()
                    sourceSize.width: 17
                    sourceSize.height: 17
                }
            }

            Column {
                anchors.verticalCenter: parent.verticalCenter
                spacing: -1
                Text {
                    text: feedback.message
                    color: "white"
                    font.pixelSize: 15
                    font.bold: true
                }
                Text {
                    visible: feedback.secondaryMessage.length > 0
                    text: feedback.secondaryMessage
                    color: feedback.danger ? "#ffffc1c1" : "#b8ffffff"
                    font.pixelSize: 11
                    font.bold: true
                }
            }
        }
    }

    component OpeningOverlay: Rectangle {
        id: opening
        property bool active: false

        visible: opacity > 0.01
        opacity: active ? 1 : 0
        color: "#e6000000"

        Behavior on opacity {
            NumberAnimation {
                duration: opening.active ? 260 : 220
                easing.type: Easing.OutCubic
            }
        }

        MouseArea {
            anchors.fill: parent
            enabled: opening.active
            acceptedButtons: Qt.AllButtons
            onClicked: function (mouse) { mouse.accepted = true; }
        }

        Image {
            id: openingArtwork
            anchors.fill: parent
            visible: playerOverlay.artworkUrl.length > 0 && status === Image.Ready
            source: playerOverlay.artworkUrl
            asynchronous: true
            fillMode: Image.PreserveAspectCrop
        }

        Rectangle {
            anchors.fill: parent
            visible: openingArtwork.visible
            gradient: Gradient {
                GradientStop {
                    position: 0.0
                    color: "#4d000000"
                }
                GradientStop {
                    position: 0.52
                    color: "#99000000"
                }
                GradientStop {
                    position: 1.0
                    color: "#e6000000"
                }
            }
        }

        HeaderButton {
            anchors.top: parent.top
            anchors.right: parent.right
            anchors.topMargin: root.safeMargin
            anchors.rightMargin: root.safeMargin
            symbol: "×"
            label: "Close"
            onTriggered: playerOverlay.closePlayer()
        }

        Column {
            id: openingContent
            anchors.centerIn: parent
            width: Math.min(parent.width - (root.safeMargin * 2), 560)
            spacing: 18
            opacity: opening.active ? 1 : 0

            SequentialAnimation on scale {
                running: opening.active
                loops: Animation.Infinite
                NumberAnimation {
                    to: 1.04
                    duration: 2000
                    easing.type: Easing.InOutSine
                }
                NumberAnimation {
                    to: 1.0
                    duration: 2000
                    easing.type: Easing.InOutSine
                }
            }

            Behavior on opacity {
                NumberAnimation {
                    duration: 700
                    easing.type: Easing.OutCubic
                }
            }

            Image {
                id: openingLogo
                anchors.horizontalCenter: parent.horizontalCenter
                visible: playerOverlay.logoUrl.length > 0 && status === Image.Ready
                source: playerOverlay.logoUrl
                asynchronous: true
                fillMode: Image.PreserveAspectFit
                sourceSize.width: 520
                width: Math.min(parent.width, 340)
                height: root.height < 460 ? 110 : 180
            }

            Text {
                width: parent.width
                anchors.horizontalCenter: parent.horizontalCenter
                visible: !openingLogo.visible && playerOverlay.title.length > 0
                text: playerOverlay.title
                color: "white"
                horizontalAlignment: Text.AlignHCenter
                font.pixelSize: root.height < 460 ? 34 : 42
                font.bold: true
                maximumLineCount: 2
                elide: Text.ElideRight
                wrapMode: Text.WordWrap
            }

            BusyIndicator {
                anchors.horizontalCenter: parent.horizontalCenter
                running: opening.active
                visible: !openingLogo.visible && playerOverlay.title.length === 0
                width: 54
                height: 54
            }
        }
    }

    component ErrorOverlay: Rectangle {
        id: errorOverlay
        property bool active: false
        property string message: ""

        visible: opacity > 0.01
        opacity: active ? 1 : 0
        color: "#e6000000"

        Behavior on opacity {
            NumberAnimation {
                duration: errorOverlay.active ? 180 : 140
                easing.type: Easing.OutCubic
            }
        }

        MouseArea {
            anchors.fill: parent
            enabled: errorOverlay.active
            acceptedButtons: Qt.AllButtons
            onClicked: function (mouse) { mouse.accepted = true; }
        }

        Column {
            id: errorCard
            anchors.centerIn: parent
            width: Math.min(parent.width - 64, 680)
            spacing: 16
            opacity: errorOverlay.active ? 1 : 0
            scale: errorOverlay.active ? 1.0 : 0.94

            Behavior on opacity {
                NumberAnimation {
                    duration: errorOverlay.active ? 210 : 120
                    easing.type: Easing.OutCubic
                }
            }

            Behavior on scale {
                NumberAnimation {
                    duration: errorOverlay.active ? 240 : 120
                    easing.type: errorOverlay.active ? Easing.OutBack : Easing.InCubic
                    easing.overshoot: 0.7
                }
            }

            Text {
                width: parent.width
                text: "Playback error"
                color: "white"
                horizontalAlignment: Text.AlignHCenter
                font.pixelSize: root.width < 720 ? 30 : 38
                font.bold: true
            }

            Text {
                width: parent.width
                text: errorOverlay.message
                color: "#b8ffffff"
                horizontalAlignment: Text.AlignHCenter
                font.pixelSize: root.width < 720 ? 16 : 18
                lineHeight: 24
                lineHeightMode: Text.FixedHeight
                maximumLineCount: 4
                elide: Text.ElideRight
                wrapMode: Text.WordWrap
            }

            Rectangle {
                anchors.horizontalCenter: parent.horizontalCenter
                width: Math.min(260, Math.max(180, goBackLabel.implicitWidth + 48))
                height: 48
                radius: 12
                color: goBackMouse.containsMouse ? "#55728dff" : "#3a556bff"
                scale: goBackMouse.pressed ? 0.97 : 1.0
                Behavior on scale {
                    NumberAnimation {
                        duration: 100
                    }
                }

                Text {
                    id: goBackLabel
                    anchors.centerIn: parent
                    text: "Go back"
                    color: "white"
                    font.pixelSize: 16
                    font.bold: true
                }

                MouseArea {
                    id: goBackMouse
                    anchors.fill: parent
                    hoverEnabled: true
                    onClicked: playerOverlay.closePlayer()
                }
            }
        }
    }

    component PauseMetadataOverlay: Rectangle {
        id: pauseOverlay
        property bool active: false
        visible: opacity > 0.01
        opacity: active ? 1 : 0
        color: "transparent"

        Behavior on opacity {
            NumberAnimation {
                duration: pauseOverlay.active ? 220 : 180
                easing.type: Easing.OutCubic
            }
        }

        Rectangle {
            anchors.fill: parent
            gradient: Gradient {
                orientation: Gradient.Horizontal
                GradientStop {
                    position: 0.0
                    color: "#d9000000"
                }
                GradientStop {
                    position: 0.58
                    color: "#73000000"
                }
                GradientStop {
                    position: 1.0
                    color: "#00000000"
                }
            }
        }

        Column {
            anchors.left: parent.left
            anchors.leftMargin: root.safeMargin + (root.width < 720 ? 10 : 28)
            anchors.right: parent.right
            anchors.rightMargin: root.safeMargin
            anchors.bottom: parent.bottom
            anchors.bottomMargin: root.height < 420 ? 42 : 120
            spacing: root.height < 420 ? 8 : 12

            Text {
                text: "You're watching"
                color: "#b8b8b8"
                font.pixelSize: root.height < 420 ? 15 : 18
                font.bold: true
            }

            Image {
                id: pauseLogo
                visible: playerOverlay.logoUrl.length > 0 && status === Image.Ready
                source: playerOverlay.logoUrl
                asynchronous: true
                fillMode: Image.PreserveAspectFit
                sourceSize.width: 520
                width: Math.min(parent.width * 0.52, 320)
                height: root.height < 420 ? 64 : 96
            }

            Text {
                width: Math.min(parent.width * 0.72, 720)
                visible: !pauseLogo.visible
                text: playerOverlay.title
                color: "white"
                font.pixelSize: root.height < 420 ? 34 : 46
                font.bold: true
                maximumLineCount: root.height < 420 ? 1 : 2
                elide: Text.ElideRight
                wrapMode: Text.WordWrap
            }

            Text {
                width: Math.min(parent.width * 0.72, 720)
                text: playerOverlay.episodeLabel.length > 0 ? playerOverlay.episodeLabel : playerOverlay.providerName
                color: "#cccccc"
                font.pixelSize: root.height < 420 ? 15 : 18
                maximumLineCount: 1
                elide: Text.ElideRight
            }

            Text {
                width: Math.min(parent.width * (root.height < 420 ? 0.82 : 0.62), 780)
                visible: playerOverlay.pauseDescription.length > 0
                text: playerOverlay.pauseDescription
                color: "#d6d6d6"
                font.pixelSize: root.height < 420 ? 15 : 17
                lineHeight: root.height < 420 ? 20 : 24
                lineHeightMode: Text.FixedHeight
                maximumLineCount: root.height < 420 ? 2 : 3
                elide: Text.ElideRight
                wrapMode: Text.WordWrap
            }
        }
    }

    component ParentalGuideOverlay: Row {
        id: guide
        property var warnings: []
        property bool active: false
        spacing: 10
        visible: opacity > 0
        opacity: active && warnings.length > 0 ? 1 : 0

        Behavior on opacity {
            NumberAnimation {
                duration: guide.active ? 300 : 220
            }
        }

        Timer {
            interval: 5600
            running: guide.active && guide.warnings.length > 0
            repeat: false
            onTriggered: playerOverlay.dismissParentalGuide()
        }

        Rectangle {
            width: 3
            height: Math.max(18, warningColumn.implicitHeight)
            radius: 2
            color: "#8bb9ffff"
        }

        Column {
            id: warningColumn
            spacing: 2
            Repeater {
                model: guide.warnings
                delegate: Row {
                    height: 18
                    spacing: 4
                    Text {
                        text: modelData.label || ""
                        color: "#d9ffffff"
                        font.pixelSize: 11
                        font.bold: true
                    }
                    Text {
                        text: "·"
                        color: "#66ffffff"
                        font.pixelSize: 11
                        font.bold: true
                    }
                    Text {
                        text: modelData.severity || ""
                        color: "#80ffffff"
                        font.pixelSize: 11
                    }
                }
            }
        }
    }

    component SubmitIntroModal: Popup {
        id: popup
        property string startDraft: playerOverlay.submitIntroStartTime
        property string endDraft: playerOverlay.submitIntroEndTime
        property string segmentDraft: playerOverlay.submitIntroSegmentType
        modal: true
        focus: true
        dim: true
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        width: Math.min(root.width * 0.9, 520)
        height: Math.min(root.height * 0.86, 500)
        x: (root.width - width) / 2
        y: (root.height - height) / 2
        transformOrigin: Item.Center
        enter: Transition {
            ParallelAnimation {
                NumberAnimation { property: "opacity"; from: 0.0; to: 1.0; duration: 220; easing.type: Easing.OutCubic }
                NumberAnimation { property: "y"; from: (root.height - popup.height) / 2 + 42; to: (root.height - popup.height) / 2; duration: 300; easing.type: Easing.OutCubic }
                NumberAnimation { property: "scale"; from: 0.985; to: 1.0; duration: 300; easing.type: Easing.OutCubic }
            }
        }
        exit: Transition {
            ParallelAnimation {
                NumberAnimation { property: "opacity"; from: 1.0; to: 0.0; duration: 170; easing.type: Easing.InCubic }
                NumberAnimation { property: "y"; from: (root.height - popup.height) / 2; to: (root.height - popup.height) / 2 + 32; duration: 210; easing.type: Easing.InCubic }
                NumberAnimation { property: "scale"; from: 1.0; to: 0.99; duration: 210; easing.type: Easing.InCubic }
            }
        }
        background: Rectangle {
            radius: 24
            color: "#ff171717"
            border.color: "#28ffffff"
            border.width: 1
        }
        onOpened: {
            startDraft = playerOverlay.submitIntroStartTime;
            endDraft = playerOverlay.submitIntroEndTime;
            segmentDraft = playerOverlay.submitIntroSegmentType;
        }

        ColumnLayout {
            anchors.fill: parent
            anchors.margins: 22
            spacing: 16

            RowLayout {
                Layout.fillWidth: true
                Text {
                    Layout.fillWidth: true
                    text: "Submit Timestamps"
                    color: "white"
                    font.pixelSize: 21
                    font.bold: true
                }
                HeaderButton {
                    label: "Close"
                    symbol: "×"
                    onTriggered: popup.close()
                }
            }

            Text {
                text: "SEGMENT TYPE"
                color: "#99ffffff"
                font.pixelSize: 11
                font.bold: true
            }

            RowLayout {
                Layout.fillWidth: true
                spacing: 8
                SegmentTypeButton {
                    Layout.fillWidth: true
                    label: "Intro"
                    symbol: "▶"
                    selected: popup.segmentDraft === "intro"
                    onTriggered: {
                        popup.segmentDraft = "intro";
                        playerOverlay.setSubmitIntroSegmentType("intro");
                    }
                }
                SegmentTypeButton {
                    Layout.fillWidth: true
                    label: "Recap"
                    symbol: "↺"
                    selected: popup.segmentDraft === "recap"
                    onTriggered: {
                        popup.segmentDraft = "recap";
                        playerOverlay.setSubmitIntroSegmentType("recap");
                    }
                }
                SegmentTypeButton {
                    Layout.fillWidth: true
                    label: "Outro"
                    symbol: "■"
                    selected: popup.segmentDraft === "outro"
                    onTriggered: {
                        popup.segmentDraft = "outro";
                        playerOverlay.setSubmitIntroSegmentType("outro");
                    }
                }
            }

            TimeCaptureRow {
                Layout.fillWidth: true
                label: "START TIME (MM:SS)"
                value: popup.startDraft
                onValueCommitted: function (value) {
                    popup.startDraft = value;
                    playerOverlay.setSubmitIntroStartTime(value);
                }
                onCaptured: {
                    popup.startDraft = root.formatSubmitTime(playerOverlay.positionMs);
                    playerOverlay.setSubmitIntroStartTime(popup.startDraft);
                }
            }

            TimeCaptureRow {
                Layout.fillWidth: true
                label: "END TIME (MM:SS)"
                value: popup.endDraft
                onValueCommitted: function (value) {
                    popup.endDraft = value;
                    playerOverlay.setSubmitIntroEndTime(value);
                }
                onCaptured: {
                    popup.endDraft = root.formatSubmitTime(playerOverlay.positionMs);
                    playerOverlay.setSubmitIntroEndTime(popup.endDraft);
                }
            }

            Item {
                Layout.fillHeight: true
            }

            RowLayout {
                Layout.fillWidth: true
                spacing: 12
                Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 48
                    radius: 12
                    color: cancelMouse.containsMouse ? "#24ffffff" : "#18ffffff"
                    Text {
                        anchors.centerIn: parent
                        text: "Cancel"
                        color: "#d5ffffff"
                        font.pixelSize: 14
                        font.bold: true
                    }
                    MouseArea {
                        id: cancelMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        enabled: !playerOverlay.submitIntroSubmitting
                        onClicked: popup.close()
                    }
                }
                Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredWidth: 230
                    Layout.preferredHeight: 48
                    radius: 12
                    color: playerOverlay.submitIntroSubmitting ? "#3a556b99" : (submitMouse.containsMouse ? "#4f6f8cff" : "#3a556bff")
                    Text {
                        anchors.centerIn: parent
                        text: playerOverlay.submitIntroSubmitting ? "Submitting..." : "Send  Submit"
                        color: "white"
                        font.pixelSize: 14
                        font.bold: true
                    }
                    BusyIndicator {
                        anchors.verticalCenter: parent.verticalCenter
                        anchors.left: parent.left
                        anchors.leftMargin: 20
                        visible: playerOverlay.submitIntroSubmitting
                        running: visible
                        width: 22
                        height: 22
                    }
                    MouseArea {
                        id: submitMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        enabled: !playerOverlay.submitIntroSubmitting
                        onClicked: {
                            playerOverlay.setSubmitIntroSegmentType(popup.segmentDraft);
                            playerOverlay.setSubmitIntroStartTime(popup.startDraft);
                            playerOverlay.setSubmitIntroEndTime(popup.endDraft);
                            playerOverlay.submitIntroTimestamps();
                        }
                    }
                }
            }
        }
    }

    component SegmentTypeButton: Rectangle {
        id: segmentButton
        property string label: ""
        property string symbol: ""
        property bool selected: false
        signal triggered
        implicitHeight: 42
        radius: 12
        color: selected ? "#3a556bff" : segmentMouse.containsMouse ? "#24ffffff" : "#18ffffff"
        border.color: selected ? "#7f8da0ff" : "#22ffffff"
        border.width: 1

        Row {
            anchors.centerIn: parent
            spacing: 7
            Text {
                text: segmentButton.symbol
                color: "white"
                font.pixelSize: 14
                font.bold: true
            }
            Text {
                text: segmentButton.label
                color: "white"
                font.pixelSize: 13
                font.bold: true
            }
        }

        MouseArea {
            id: segmentMouse
            anchors.fill: parent
            hoverEnabled: true
            onClicked: segmentButton.triggered()
        }
    }

    component TimeCaptureRow: RowLayout {
        id: timeRow
        property string label: ""
        property string value: ""
        signal valueCommitted(string value)
        signal captured
        spacing: 12

        ColumnLayout {
            Layout.fillWidth: true
            spacing: 8
            Text {
                text: timeRow.label
                color: "#99ffffff"
                font.pixelSize: 11
                font.bold: true
            }
            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: 48
                radius: 12
                color: "#18ffffff"
                border.color: "#30ffffff"
                border.width: 1
                TextInput {
                    id: timeInput
                    anchors.fill: parent
                    anchors.leftMargin: 14
                    anchors.rightMargin: 14
                    verticalAlignment: TextInput.AlignVCenter
                    text: timeRow.value
                    color: "white"
                    selectionColor: "#668bb9ff"
                    selectedTextColor: "white"
                    font.pixelSize: 17
                    onTextEdited: timeRow.valueCommitted(text)
                    onEditingFinished: timeRow.valueCommitted(text)
                }
            }
        }

        Rectangle {
            Layout.preferredWidth: 118
            Layout.preferredHeight: 48
            Layout.alignment: Qt.AlignBottom
            radius: 12
            color: captureMouse.containsMouse ? "#24ffffff" : "#18ffffff"
            border.color: "#22ffffff"
            border.width: 1
            Row {
                anchors.centerIn: parent
                spacing: 6
                Text {
                    text: "⌖"
                    color: "#d5ffffff"
                    font.pixelSize: 15
                    font.bold: true
                }
                Text {
                    text: "Capture"
                    color: "#d5ffffff"
                    font.pixelSize: 13
                    font.bold: true
                }
            }
            MouseArea {
                id: captureMouse
                anchors.fill: parent
                hoverEnabled: true
                onClicked: timeRow.captured()
            }
        }
    }

    component InfoModal: Popup {
        id: popup
        property string title: ""
        property string message: ""
        modal: true
        focus: true
        dim: true
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        width: Math.min(root.width * 0.9, 520)
        height: 220
        x: (root.width - width) / 2
        y: (root.height - height) / 2
        transformOrigin: Item.Center
        enter: Transition {
            ParallelAnimation {
                NumberAnimation { property: "opacity"; from: 0.0; to: 1.0; duration: 220; easing.type: Easing.OutCubic }
                NumberAnimation { property: "y"; from: (root.height - popup.height) / 2 + 42; to: (root.height - popup.height) / 2; duration: 300; easing.type: Easing.OutCubic }
                NumberAnimation { property: "scale"; from: 0.985; to: 1.0; duration: 300; easing.type: Easing.OutCubic }
            }
        }
        exit: Transition {
            ParallelAnimation {
                NumberAnimation { property: "opacity"; from: 1.0; to: 0.0; duration: 170; easing.type: Easing.InCubic }
                NumberAnimation { property: "y"; from: (root.height - popup.height) / 2; to: (root.height - popup.height) / 2 + 32; duration: 210; easing.type: Easing.InCubic }
                NumberAnimation { property: "scale"; from: 1.0; to: 0.99; duration: 210; easing.type: Easing.InCubic }
            }
        }
        background: Rectangle {
            radius: 24
            color: "#ff171717"
            border.color: "#28ffffff"
            border.width: 1
        }

        ColumnLayout {
            anchors.fill: parent
            anchors.margins: 22
            spacing: 10
            Text {
                text: popup.title
                color: "white"
                font.pixelSize: 20
                font.bold: true
            }
            Text {
                Layout.fillWidth: true
                text: popup.message
                color: "#caffffff"
                font.pixelSize: 14
                wrapMode: Text.WordWrap
            }
            Item {
                Layout.fillHeight: true
            }
            Button {
                Layout.alignment: Qt.AlignRight
                text: "Close"
                onClicked: popup.close()
            }
        }
    }

    component SourceModal: Popup {
        id: popup
        modal: true
        focus: true
        dim: true
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        width: Math.min(root.width * 0.92, 540)
        height: Math.min(root.height * 0.84, 620)
        x: (root.width - width) / 2
        y: (root.height - height) / 2
        transformOrigin: Item.Center
        enter: Transition {
            ParallelAnimation {
                NumberAnimation { property: "opacity"; from: 0.0; to: 1.0; duration: 220; easing.type: Easing.OutCubic }
                NumberAnimation { property: "y"; from: (root.height - popup.height) / 2 + 42; to: (root.height - popup.height) / 2; duration: 300; easing.type: Easing.OutCubic }
                NumberAnimation { property: "scale"; from: 0.985; to: 1.0; duration: 300; easing.type: Easing.OutCubic }
            }
        }
        exit: Transition {
            ParallelAnimation {
                NumberAnimation { property: "opacity"; from: 1.0; to: 0.0; duration: 170; easing.type: Easing.InCubic }
                NumberAnimation { property: "y"; from: (root.height - popup.height) / 2; to: (root.height - popup.height) / 2 + 32; duration: 210; easing.type: Easing.InCubic }
                NumberAnimation { property: "scale"; from: 1.0; to: 0.99; duration: 210; easing.type: Easing.InCubic }
            }
        }
        background: Rectangle {
            radius: 24
            color: "#ff171717"
            border.color: "#28ffffff"
            border.width: 1
        }

        ColumnLayout {
            anchors.fill: parent
            anchors.margins: 20
            spacing: 12

            RowLayout {
                Layout.fillWidth: true
                Text {
                    Layout.fillWidth: true
                    text: "Sources"
                    color: "white"
                    font.pixelSize: 20
                    font.bold: true
                }
                PanelChipButton {
                    iconSource: root.iconRefresh
                    label: "Reload"
                    onTriggered: playerOverlay.reloadSources()
                }
                PanelChipButton {
                    label: "Close"
                    onTriggered: popup.close()
                }
            }

            FilterStrip {
                Layout.fillWidth: true
                visible: playerOverlay.sourceFilters.length > 1
                model: playerOverlay.sourceFilters
                onSelected: function (index) {
                    playerOverlay.selectSourceFilter(index);
                }
            }

            BusyIndicator {
                Layout.alignment: Qt.AlignHCenter
                visible: playerOverlay.sourcesLoading && playerOverlay.sources.length === 0
                running: visible
            }

            ListView {
                Layout.fillWidth: true
                Layout.fillHeight: true
                clip: true
                spacing: 8
                model: playerOverlay.sources
                delegate: DataRow {
                    width: ListView.view.width
                    title: modelData.label || ""
                    subtitle: modelData.subtitle || ""
                    sizeText: modelData.size || ""
                    providerText: modelData.provider || ""
                    selected: modelData.current === true
                    statusText: modelData.current === true ? "Playing" : ""
                    onTriggered: {
                        playerOverlay.selectSource(modelData.index);
                        popup.close();
                    }
                }
            }

            Text {
                visible: !playerOverlay.sourcesLoading && playerOverlay.sources.length === 0
                Layout.alignment: Qt.AlignHCenter
                text: "No streams found"
                color: "#b8ffffff"
                font.pixelSize: 14
            }
        }
    }

    component EpisodeModal: Popup {
        id: popup
        property int selectedSeason: -9999
        function showingStreams() {
            return playerOverlay.episodeStreams.length > 0 || playerOverlay.episodeStreamsLoading;
        }
        function seasonValue(episode) {
            var value = Number(episode.season);
            if (isNaN(value))
                return 0;
            return value;
        }
        function seasonLabel(season) {
            return season === 0 ? "Specials" : "Season " + season;
        }
        function seasonRows() {
            var seen = {};
            var seasons = [];
            for (var i = 0; i < playerOverlay.episodes.length; ++i) {
                var season = seasonValue(playerOverlay.episodes[i]);
                if (season < 0)
                    season = 0;
                if (seen[season] === true)
                    continue;
                seen[season] = true;
                seasons.push(season);
            }
            seasons.sort(function (a, b) {
                if (a === 0 && b !== 0)
                    return 1;
                if (b === 0 && a !== 0)
                    return -1;
                return a - b;
            });
            var rows = [];
            for (var j = 0; j < seasons.length; ++j) {
                rows.push({
                    index: seasons[j],
                    label: seasonLabel(seasons[j]),
                    selected: seasons[j] === selectedSeason
                });
            }
            return rows;
        }
        function filteredEpisodes() {
            var seasons = seasonRows();
            if (seasons.length === 0)
                return [];
            var effectiveSeason = selectedSeason === -9999 ? seasons[0].index : selectedSeason;
            var rows = [];
            for (var i = 0; i < playerOverlay.episodes.length; ++i) {
                var episode = playerOverlay.episodes[i];
                var season = seasonValue(episode);
                if (season < 0)
                    season = 0;
                if (season === effectiveSeason)
                    rows.push(episode);
            }
            return rows;
        }
        modal: true
        focus: true
        dim: true
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        width: Math.min(root.width * 0.92, 560)
        height: Math.min(root.height * 0.86, 640)
        x: (root.width - width) / 2
        y: (root.height - height) / 2
        transformOrigin: Item.Center
        enter: Transition {
            ParallelAnimation {
                NumberAnimation { property: "opacity"; from: 0.0; to: 1.0; duration: 220; easing.type: Easing.OutCubic }
                NumberAnimation { property: "y"; from: (root.height - popup.height) / 2 + 42; to: (root.height - popup.height) / 2; duration: 300; easing.type: Easing.OutCubic }
                NumberAnimation { property: "scale"; from: 0.985; to: 1.0; duration: 300; easing.type: Easing.OutCubic }
            }
        }
        exit: Transition {
            ParallelAnimation {
                NumberAnimation { property: "opacity"; from: 1.0; to: 0.0; duration: 170; easing.type: Easing.InCubic }
                NumberAnimation { property: "y"; from: (root.height - popup.height) / 2; to: (root.height - popup.height) / 2 + 32; duration: 210; easing.type: Easing.InCubic }
                NumberAnimation { property: "scale"; from: 1.0; to: 0.99; duration: 210; easing.type: Easing.InCubic }
            }
        }
        background: Rectangle {
            radius: 24
            color: "#ff171717"
            border.color: "#28ffffff"
            border.width: 1
        }
        onOpened: {
            if (!showingStreams()) {
                var current = Number(playerOverlay.currentSeason);
                selectedSeason = current >= 0 ? current : -9999;
                var seasons = seasonRows();
                var found = false;
                for (var i = 0; i < seasons.length; ++i) {
                    if (seasons[i].index === selectedSeason) {
                        found = true;
                        break;
                    }
                }
                if (seasons.length > 0 && !found)
                    selectedSeason = seasons[0].index;
            }
        }

        ColumnLayout {
            anchors.fill: parent
            anchors.margins: 20
            spacing: 12

            RowLayout {
                Layout.fillWidth: true
                Text {
                    Layout.fillWidth: true
                    text: popup.showingStreams() ? "Streams" : "Episodes"
                    color: "white"
                    font.pixelSize: 20
                    font.bold: true
                }
                PanelChipButton {
                    visible: popup.showingStreams()
                    iconSource: root.iconBack
                    label: "Back"
                    onTriggered: playerOverlay.backToEpisodes()
                }
                PanelChipButton {
                    visible: popup.showingStreams()
                    iconSource: root.iconRefresh
                    label: "Reload"
                    onTriggered: playerOverlay.reloadEpisodeStreams()
                }
                PanelChipButton {
                    label: "Close"
                    onTriggered: popup.close()
                }
            }

            Text {
                Layout.fillWidth: true
                visible: playerOverlay.selectedEpisodeLabel.length > 0 && popup.showingStreams()
                text: playerOverlay.selectedEpisodeLabel
                color: "#b8ffffff"
                font.pixelSize: 13
                elide: Text.ElideRight
            }

            FilterStrip {
                Layout.fillWidth: true
                visible: popup.showingStreams()
                    ? playerOverlay.episodeStreamFilters.length > 1
                    : popup.seasonRows().length > 1
                model: popup.showingStreams() ? playerOverlay.episodeStreamFilters : popup.seasonRows()
                onSelected: function (index) {
                    if (popup.showingStreams())
                        playerOverlay.selectEpisodeStreamFilter(index);
                    else
                        popup.selectedSeason = index;
                }
            }

            BusyIndicator {
                Layout.alignment: Qt.AlignHCenter
                visible: playerOverlay.episodeStreamsLoading && playerOverlay.episodeStreams.length === 0
                running: visible
            }

            ListView {
                Layout.fillWidth: true
                Layout.fillHeight: true
                clip: true
                spacing: 8
                model: popup.showingStreams() ? playerOverlay.episodeStreams : popup.filteredEpisodes()
                delegate: DataRow {
                    width: ListView.view.width
                    title: modelData.title || modelData.label || ""
                    subtitle: modelData.subtitle || modelData.overview || ""
                    thumbnail: popup.showingStreams() ? "" : (modelData.thumbnail || "")
                    meta: popup.showingStreams() ? "" : (modelData.label || "")
                    sizeText: popup.showingStreams() ? (modelData.size || "") : ""
                    providerText: popup.showingStreams() ? (modelData.provider || "") : ""
                    statusText: modelData.current === true ? "Playing" : (modelData.watched === true ? "Watched" : "")
                    progressPercent: popup.showingStreams() ? -1 : (Number(modelData.progressPercent) || -1)
                    dimThumbnail: modelData.blurArtwork === true
                    selected: modelData.current === true
                    onTriggered: {
                        if (popup.showingStreams()) {
                            playerOverlay.selectEpisodeStream(modelData.index);
                            popup.close();
                        } else {
                            playerOverlay.selectEpisode(modelData.index);
                        }
                    }
                }
            }

            Text {
                visible: !playerOverlay.episodeStreamsLoading && (popup.showingStreams() ? playerOverlay.episodeStreams.length === 0 : popup.filteredEpisodes().length === 0)
                Layout.alignment: Qt.AlignHCenter
                text: popup.showingStreams() ? "No streams found" : "No episodes available"
                color: "#b8ffffff"
                font.pixelSize: 14
            }
        }
    }

    component DataRow: Rectangle {
        id: row
        property string title: ""
        property string subtitle: ""
        property string meta: ""
        property string sizeText: ""
        property string providerText: ""
        property string thumbnail: ""
        property string statusText: ""
        property int progressPercent: -1
        property bool dimThumbnail: false
        property bool selected: false
        signal triggered
        height: Math.max(thumbnail.length > 0 ? 74 : 66, content.implicitHeight + 22)
        radius: 12
        color: selected ? "#3a556bff" : rowMouse.containsMouse ? "#24ffffff" : "#14ffffff"
        border.color: selected ? "#7f8da0ff" : "transparent"
        border.width: 1

        RowLayout {
            id: content
            anchors.fill: parent
            anchors.leftMargin: 12
            anchors.rightMargin: 12
            anchors.topMargin: 10
            anchors.bottomMargin: 10
            spacing: 12

            Rectangle {
                visible: row.thumbnail.length > 0
                Layout.preferredWidth: 80
                Layout.preferredHeight: 48
                radius: 8
                color: "#16ffffff"
                clip: true

                Image {
                    anchors.fill: parent
                    source: row.thumbnail
                    fillMode: Image.PreserveAspectCrop
                    asynchronous: true
                    cache: true
                    opacity: row.dimThumbnail ? 0.34 : 1.0
                    visible: status === Image.Ready
                }
                Rectangle {
                    anchors.fill: parent
                    visible: row.dimThumbnail
                    color: "#66000000"
                }
            }

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 4

                RowLayout {
                    Layout.fillWidth: true
                    spacing: 8
                    Text {
                        Layout.fillWidth: true
                        text: row.title
                        color: "white"
                        font.pixelSize: 14
                        font.bold: true
                        elide: Text.ElideRight
                        maximumLineCount: 2
                        wrapMode: Text.WordWrap
                    }
                    Rectangle {
                        visible: row.statusText.length > 0
                        Layout.preferredWidth: statusLabel.implicitWidth + 14
                        Layout.preferredHeight: 22
                        radius: 11
                        color: "#4f6f8cff"
                        border.color: "#66ffffff"
                        border.width: 1
                        Text {
                            id: statusLabel
                            anchors.centerIn: parent
                            text: row.statusText
                            color: "white"
                            font.pixelSize: 10
                            font.bold: true
                        }
                    }
                }

                Text {
                    Layout.fillWidth: true
                    visible: row.subtitle.length > 0
                    text: row.subtitle
                    color: "#b8ffffff"
                    font.pixelSize: 12
                    elide: Text.ElideRight
                    maximumLineCount: row.thumbnail.length > 0 ? 2 : 2
                    wrapMode: Text.WordWrap
                }
                Text {
                    Layout.fillWidth: true
                    visible: row.meta.length > 0 && row.sizeText.length === 0 && row.providerText.length === 0
                    text: row.meta
                    color: "#96ffffff"
                    font.pixelSize: 11
                    font.italic: true
                    elide: Text.ElideRight
                }
                RowLayout {
                    Layout.fillWidth: true
                    visible: row.sizeText.length > 0 || row.providerText.length > 0
                    spacing: 8

                    Rectangle {
                        visible: row.sizeText.length > 0
                        Layout.preferredWidth: sizeLabel.implicitWidth + 16
                        Layout.preferredHeight: 22
                        radius: 11
                        color: "#ff0a0c0c"
                        Text {
                            id: sizeLabel
                            anchors.centerIn: parent
                            text: row.sizeText
                            color: "white"
                            font.pixelSize: 11
                            font.bold: true
                        }
                    }

                    Text {
                        Layout.fillWidth: true
                        visible: row.providerText.length > 0
                        text: row.providerText
                        color: "#96ffffff"
                        font.pixelSize: 11
                        font.italic: true
                        elide: Text.ElideRight
                    }
                }
            }

            Text {
                visible: row.selected && row.statusText.length === 0
                text: "✓"
                color: "white"
                font.pixelSize: 18
                font.bold: true
            }
        }

        MouseArea {
            id: rowMouse
            anchors.fill: parent
            hoverEnabled: true
            onClicked: row.triggered()
        }

        Rectangle {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            height: 3
            radius: 2
            visible: row.progressPercent > 0 && row.progressPercent < 100 && !row.selected
            color: "#18ffffff"
            clip: true

            Rectangle {
                anchors.left: parent.left
                anchors.top: parent.top
                anchors.bottom: parent.bottom
                width: parent.width * Math.max(0, Math.min(100, row.progressPercent)) / 100
                radius: 2
                color: "#8bb9ffff"
            }
        }
    }

    component FilterStrip: Flickable {
        id: strip
        property var model: []
        signal selected(int index)
        implicitHeight: 38
        contentWidth: row.implicitWidth
        clip: true

        Row {
            id: row
            spacing: 8
            Repeater {
                model: strip.model
                delegate: Rectangle {
                    height: 34
                    width: chipText.implicitWidth + 28
                    radius: 17
                    color: modelData.selected === true ? "#3a556bff" : chipMouse.containsMouse ? "#24ffffff" : "#18ffffff"
                    border.color: modelData.error === true ? "#ccff6b6b" : (modelData.selected === true ? "#7f8da0ff" : "#22ffffff")
                    border.width: 1

                    Row {
                        anchors.centerIn: parent
                        spacing: 6
                        BusyIndicator {
                            visible: modelData.loading === true
                            running: visible
                            width: 12
                            height: 12
                        }
                        Text {
                            id: chipText
                            text: modelData.label || ""
                            color: modelData.error === true ? "#ffffb4b4" : "white"
                            font.pixelSize: 12
                            font.bold: modelData.selected === true
                        }
                    }

                    MouseArea {
                        id: chipMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        onClicked: strip.selected(modelData.index)
                    }
                }
            }
        }
    }

    component StepperButton: Rectangle {
        id: stepperButton
        property string symbol: ""
        signal triggered
        implicitWidth: 30
        implicitHeight: 30
        radius: 10
        color: stepperMouse.containsMouse ? "#4f6f8cff" : "#344f68ff"
        scale: stepperMouse.pressed ? 0.94 : 1.0

        Text {
            anchors.centerIn: parent
            text: stepperButton.symbol
            color: "white"
            font.pixelSize: 17
            font.bold: true
        }

        MouseArea {
            id: stepperMouse
            anchors.fill: parent
            hoverEnabled: true
            onClicked: stepperButton.triggered()
        }
    }

    component SubtitleStyleControls: Rectangle {
        implicitHeight: 260
        radius: 16
        color: "#18ffffff"
        border.color: "#22ffffff"
        border.width: 1

        ColumnLayout {
            anchors.fill: parent
            anchors.margins: 14
            spacing: 10

            RowLayout {
                Layout.fillWidth: true
                Text {
                    Layout.fillWidth: true
                    text: "Style"
                    color: "white"
                    font.pixelSize: 14
                    font.bold: true
                }
            }

            RowLayout {
                Layout.fillWidth: true
                spacing: 12
                Text {
                    Layout.fillWidth: true
                    text: "Font size"
                    color: "#cfffffff"
                    font.pixelSize: 13
                }
                StepperButton {
                    symbol: "⌄"
                    onTriggered: playerOverlay.setSubtitleFontSizeSp(playerOverlay.subtitleFontSizeSp - 2)
                }
                Text {
                    Layout.preferredWidth: 52
                    horizontalAlignment: Text.AlignHCenter
                    text: playerOverlay.subtitleFontSizeSp + "sp"
                    color: "white"
                    font.pixelSize: 13
                    font.bold: true
                }
                StepperButton {
                    symbol: "⌃"
                    onTriggered: playerOverlay.setSubtitleFontSizeSp(playerOverlay.subtitleFontSizeSp + 2)
                }
            }

            RowLayout {
                Layout.fillWidth: true
                spacing: 12
                Text {
                    Layout.fillWidth: true
                    text: "Outline"
                    color: "#cfffffff"
                    font.pixelSize: 13
                }
                Rectangle {
                    implicitWidth: outlineLabel.implicitWidth + 20
                    implicitHeight: 32
                    radius: 10
                    color: playerOverlay.subtitleOutlineEnabled ? "#3a556bff" : "#d1171717"
                    border.color: playerOverlay.subtitleOutlineEnabled ? "#7f8da0ff" : "#22ffffff"
                    Text {
                        id: outlineLabel
                        anchors.centerIn: parent
                        text: playerOverlay.subtitleOutlineEnabled ? "On" : "Off"
                        color: "white"
                        font.pixelSize: 13
                        font.bold: true
                    }
                    MouseArea {
                        anchors.fill: parent
                        hoverEnabled: true
                        onClicked: playerOverlay.setSubtitleOutlineEnabled(!playerOverlay.subtitleOutlineEnabled)
                    }
                }
            }

            RowLayout {
                Layout.fillWidth: true
                spacing: 12
                Text {
                    Layout.fillWidth: true
                    text: "Bottom offset"
                    color: "#cfffffff"
                    font.pixelSize: 13
                }
                StepperButton {
                    symbol: "⌄"
                    onTriggered: playerOverlay.setSubtitleBottomOffset(playerOverlay.subtitleBottomOffset - 5)
                }
                Text {
                    Layout.preferredWidth: 46
                    horizontalAlignment: Text.AlignHCenter
                    text: playerOverlay.subtitleBottomOffset
                    color: "white"
                    font.pixelSize: 13
                    font.bold: true
                }
                StepperButton {
                    symbol: "⌃"
                    onTriggered: playerOverlay.setSubtitleBottomOffset(playerOverlay.subtitleBottomOffset + 5)
                }
            }

            RowLayout {
                Layout.fillWidth: true
                Text {
                    text: "Color"
                    color: "#cfffffff"
                    font.pixelSize: 14
                    font.bold: true
                }
            }

            Row {
                Layout.fillWidth: true
                spacing: 8
                Repeater {
                    model: ["#FFFFFF", "#FFD700", "#00E5FF", "#FF5C5C", "#00FF88", "#9B59B6", "#F97316", "#22C55E", "#3B82F6", "#000000"]
                    delegate: Rectangle {
                        required property string modelData
                        width: 22
                        height: 22
                        radius: 11
                        color: modelData
                        border.width: 2
                        border.color: playerOverlay.subtitleTextColor === modelData ? "#8bb9ffff" : "#99ffffff"
                        MouseArea {
                            anchors.fill: parent
                            onClicked: playerOverlay.setSubtitleTextColor(modelData)
                        }
                    }
                }
            }

            RowLayout {
                Layout.fillWidth: true
                Item {
                    Layout.fillWidth: true
                }
                Rectangle {
                    implicitWidth: resetText.implicitWidth + 24
                    implicitHeight: 34
                    radius: 8
                    color: resetMouse.containsMouse ? "#24ffffff" : "#d1171717"
                    border.color: "#22ffffff"
                    Text {
                        id: resetText
                        anchors.centerIn: parent
                        text: "Reset Defaults"
                        color: "white"
                        font.pixelSize: 14
                        font.bold: true
                    }
                    MouseArea {
                        id: resetMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        onClicked: playerOverlay.resetSubtitleStyle()
                    }
                }
            }
        }
    }

    component ModalTabButton: Rectangle {
        id: tabButton
        property string label: ""
        property bool selected: false
        property bool busy: false
        signal triggered
        implicitHeight: 36
        radius: selected ? 10 : 18
        color: selected ? "#3a556bff" : (tabMouse.containsMouse ? "#24ffffff" : "#18ffffff")
        border.color: selected ? "#7f8da0ff" : "#22ffffff"
        border.width: 1

        Behavior on radius {
            NumberAnimation {
                duration: 180
                easing.type: Easing.OutCubic
            }
        }

        Behavior on color {
            ColorAnimation {
                duration: 160
                easing.type: Easing.OutCubic
            }
        }

        Row {
            anchors.centerIn: parent
            spacing: 6
            BusyIndicator {
                visible: tabButton.busy
                running: visible
                width: 12
                height: 12
            }
            Text {
                text: tabButton.label
                color: "white"
                font.pixelSize: 13
                font.bold: tabButton.selected
            }
        }

        MouseArea {
            id: tabMouse
            anchors.fill: parent
            hoverEnabled: true
            onClicked: tabButton.triggered()
        }
    }

    component TrackModal: Popup {
        id: popup
        property string title: ""
        property string emptyText: ""
        property var model: []
        property var addonModel: []
        property int selectedIndex: -1
        property int activeTab: 0
        property int closeDelayMs: 0
        property bool includeNone: false
        property bool addonMode: false
        property bool addonLoading: false
        signal selected(int index)
        signal fetchAddons
        signal addonSelected(int index)
        function maybeFetchAddons() {
            if (popup.addonMode && popup.activeTab === 1 && popup.addonModel.length === 0 && !popup.addonLoading)
                popup.fetchAddons();
        }
        function closeAfterSelection() {
            if (popup.closeDelayMs > 0)
                selectionCloseTimer.restart();
            else
                popup.close();
        }
        modal: true
        focus: true
        dim: true
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        width: Math.min(root.width * 0.9, 430)
        height: Math.min(root.height * 0.82, 600)
        x: (root.width - width) / 2
        y: (root.height - height) / 2
        transformOrigin: Item.Center
        enter: Transition {
            ParallelAnimation {
                NumberAnimation { property: "opacity"; from: 0.0; to: 1.0; duration: 220; easing.type: Easing.OutCubic }
                NumberAnimation { property: "y"; from: (root.height - popup.height) / 2 + 42; to: (root.height - popup.height) / 2; duration: 300; easing.type: Easing.OutCubic }
                NumberAnimation { property: "scale"; from: 0.985; to: 1.0; duration: 300; easing.type: Easing.OutCubic }
            }
        }
        exit: Transition {
            ParallelAnimation {
                NumberAnimation { property: "opacity"; from: 1.0; to: 0.0; duration: 170; easing.type: Easing.InCubic }
                NumberAnimation { property: "y"; from: (root.height - popup.height) / 2; to: (root.height - popup.height) / 2 + 32; duration: 210; easing.type: Easing.InCubic }
                NumberAnimation { property: "scale"; from: 1.0; to: 0.99; duration: 210; easing.type: Easing.InCubic }
            }
        }
        background: Rectangle {
            radius: 24
            color: "#ff171717"
            border.color: "#28ffffff"
            border.width: 1
        }
        onActiveTabChanged: popup.maybeFetchAddons()
        onOpened: popup.maybeFetchAddons()

        Timer {
            id: selectionCloseTimer
            interval: popup.closeDelayMs
            repeat: false
            onTriggered: popup.close()
        }

        ColumnLayout {
            anchors.fill: parent
            anchors.margins: 20
            spacing: 12

            Text {
                text: popup.title
                color: "white"
                font.pixelSize: 20
                font.bold: true
            }

            RowLayout {
                Layout.fillWidth: true
                visible: popup.addonMode
                spacing: 10
                ModalTabButton {
                    Layout.fillWidth: true
                    label: "Built-in"
                    selected: popup.activeTab === 0
                    onTriggered: popup.activeTab = 0
                }
                ModalTabButton {
                    Layout.fillWidth: true
                    label: "Add-ons"
                    selected: popup.activeTab === 1
                    busy: popup.addonLoading
                    onTriggered: popup.activeTab = 1
                }
                ModalTabButton {
                    Layout.fillWidth: true
                    label: "Style"
                    selected: popup.activeTab === 2
                    onTriggered: popup.activeTab = 2
                }
            }

            ListView {
                visible: !popup.addonMode
                    || popup.activeTab === 0
                    || (popup.activeTab === 1 && !popup.addonLoading && popup.addonModel.length > 0)
                Layout.fillWidth: true
                Layout.fillHeight: true
                clip: true
                spacing: 8
                model: {
                    var rows = [];
                    if (!popup.addonMode || popup.activeTab === 0) {
                        if (popup.includeNone)
                            rows.push({
                                index: -1,
                                label: "None",
                                language: ""
                            });
                        for (var i = 0; i < popup.model.length; ++i)
                            rows.push({
                                kind: "builtin",
                                index: popup.model[i].index,
                                label: popup.model[i].label,
                                language: popup.model[i].language
                            });
                    } else if (popup.activeTab === 1) {
                        if (!popup.addonLoading) {
                            for (var j = 0; j < popup.addonModel.length; ++j) {
                                rows.push({
                                    kind: "addon",
                                    index: popup.addonModel[j].index,
                                    label: popup.addonModel[j].label,
                                    language: popup.addonModel[j].language,
                                    selected: popup.addonModel[j].selected
                                });
                            }
                        }
                    }
                    return rows;
                }
                delegate: Rectangle {
                    width: ListView.view.width
                    height: modelData.kind === "addon" ? 58 : (modelData.kind === "section" ? 28 : 46)
                    radius: modelData.kind === "section" ? 0 : 12
                    color: modelData.kind === "section" ? "transparent" : (modelData.selected === true || (modelData.kind !== "addon" && modelData.index === popup.selectedIndex) ? "#3a556bff" : "#18ffffff")
                    border.color: modelData.selected === true || (modelData.kind !== "addon" && modelData.index === popup.selectedIndex) ? "#7f8da0ff" : "transparent"
                    border.width: 1

                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 12
                        anchors.rightMargin: 12
                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 2
                            Text {
                                Layout.fillWidth: true
                                text: (modelData.label && modelData.label.length > 0) ? modelData.label : ((modelData.language && modelData.language.length > 0) ? modelData.language : "Track " + modelData.index)
                                color: "white"
                                font.pixelSize: modelData.kind === "section" ? 12 : 15
                                font.bold: modelData.kind === "section" || modelData.selected === true || (modelData.kind !== "addon" && modelData.index === popup.selectedIndex)
                                elide: Text.ElideRight
                            }
                            Text {
                                Layout.fillWidth: true
                                visible: modelData.kind === "addon" && modelData.language && modelData.language.length > 0
                                text: root.languageName(modelData.language)
                                color: "#b8ffffff"
                                font.pixelSize: 11
                                elide: Text.ElideRight
                            }
                        }
                        Text {
                            visible: modelData.selected === true || (modelData.kind !== "addon" && modelData.index === popup.selectedIndex)
                            text: "✓"
                            color: "white"
                            font.pixelSize: 18
                            font.bold: true
                        }
                    }

                    MouseArea {
                        anchors.fill: parent
                        enabled: modelData.kind !== "section"
                        onClicked: {
                            if (modelData.kind === "addon") {
                                popup.addonSelected(modelData.index);
                            } else {
                                popup.selected(modelData.index);
                            }
                        }
                    }
                }
            }

            Item {
                visible: popup.addonMode && popup.activeTab === 1 && (popup.addonLoading || popup.addonModel.length === 0)
                Layout.fillWidth: true
                Layout.fillHeight: true

                Column {
                    anchors.centerIn: parent
                    width: parent.width
                    spacing: 10

                    BusyIndicator {
                        visible: popup.addonLoading
                        running: visible
                        width: 32
                        height: 32
                        anchors.horizontalCenter: parent.horizontalCenter
                    }

                    Image {
                        visible: !popup.addonLoading
                        source: root.iconCloudDownload
                        width: 34
                        height: 34
                        anchors.horizontalCenter: parent.horizontalCenter
                        opacity: fetchMouse.containsMouse ? 0.95 : 0.72
                        sourceSize.width: 34
                        sourceSize.height: 34
                    }

                    Text {
                        anchors.horizontalCenter: parent.horizontalCenter
                        text: popup.addonLoading ? "Fetching subtitles..." : "Fetch subtitles"
                        color: "#c8ffffff"
                        font.pixelSize: 14
                        font.bold: !popup.addonLoading
                    }
                }

                MouseArea {
                    id: fetchMouse
                    anchors.fill: parent
                    hoverEnabled: true
                    enabled: !popup.addonLoading
                    onClicked: popup.fetchAddons()
                }
            }

            SubtitleStyleControls {
                visible: popup.addonMode && popup.activeTab === 2
                Layout.fillWidth: true
                Layout.fillHeight: true
            }

            Text {
                visible: (!popup.addonMode && popup.model.length === 0 && !popup.includeNone)
                    || (popup.addonMode && popup.activeTab === 0 && popup.model.length === 0 && !popup.includeNone)
                Layout.alignment: Qt.AlignHCenter
                text: popup.emptyText
                color: "#b8ffffff"
                font.pixelSize: 14
            }
        }
    }
}
