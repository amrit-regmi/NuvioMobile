#include "player_overlay_controller.h"

#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QUrl>
#include <QtMath>

#include <cmath>

namespace {

constexpr int kAutoHideDelayMs = 3600;
constexpr int kGestureFeedbackDelayMs = 900;
constexpr int kDoubleTapSeekResetDelayMs = 650;
constexpr qint64 kDoubleTapSeekStepMs = 10000;

QString displayNameFromUrl(const QString &rawUrl)
{
    const QUrl url(rawUrl);
    const auto path = url.isValid() ? url.path() : rawUrl;
    auto name = QFileInfo(path).completeBaseName();
    if (name.isEmpty()) name = QFileInfo(path).fileName();
    if (name.isEmpty()) name = QStringLiteral("Now playing");
    return QUrl::fromPercentEncoding(name.toUtf8()).replace('.', ' ').replace('_', ' ');
}

QString normalizeColor(const QString &color)
{
    auto clean = color.trimmed();
    if (!clean.startsWith(QLatin1Char('#'))) clean.prepend(QLatin1Char('#'));
    if (clean.size() != 7) return QStringLiteral("#FFFFFF");
    return clean.toUpper();
}

QString formatPlayerTime(qint64 millis)
{
    const auto total = qMax<qint64>(0, millis / 1000);
    const auto hours = total / 3600;
    const auto minutes = (total % 3600) / 60;
    const auto seconds = total % 60;
    const auto paddedSeconds = QStringLiteral("%1").arg(seconds, 2, 10, QLatin1Char('0'));
    if (hours > 0) {
        return QStringLiteral("%1:%2:%3")
            .arg(hours)
            .arg(minutes, 2, 10, QLatin1Char('0'))
            .arg(paddedSeconds);
    }
    return QStringLiteral("%1:%2").arg(minutes).arg(paddedSeconds);
}

QString buildNextEpisodeStatusText(const QJsonObject &context)
{
    if (!context.value(QStringLiteral("nextEpisodePlayable")).toBool(true)) {
        return context.value(QStringLiteral("nextEpisodeUnairedMessage")).toString();
    }
    if (context.value(QStringLiteral("nextEpisodeAutoPlaySearching")).toBool(false)) {
        return QStringLiteral("Finding source...");
    }
    const auto sourceName = context.value(QStringLiteral("nextEpisodeAutoPlaySourceName")).toString().trimmed();
    const auto countdown = context.value(QStringLiteral("nextEpisodeAutoPlayCountdown")).toInt(-1);
    if (!sourceName.isEmpty() && countdown >= 0) {
        return QStringLiteral("Playing via %1 in %2s").arg(sourceName).arg(countdown);
    }
    return QString();
}

QVariantList jsonArrayToVariantList(const QJsonArray &array)
{
    QVariantList list;
    list.reserve(array.size());
    for (const auto &item : array) {
        list.append(item.toObject().toVariantMap());
    }
    return list;
}

int resizeModeFromContext(const QJsonObject &context, int fallback)
{
    const auto raw = context.value(QStringLiteral("resizeMode"));
    if (raw.isDouble()) return qBound(0, raw.toInt(fallback), 2);

    const auto name = raw.toString().trimmed();
    if (name == QLatin1String("Fill")) return 1;
    if (name == QLatin1String("Zoom")) return 2;
    if (name == QLatin1String("Fit")) return 0;
    return fallback;
}

} // namespace

PlayerOverlayController::PlayerOverlayController(QObject *parent)
    : QObject(parent)
{
    m_autoHideTimer.setSingleShot(true);
    m_autoHideTimer.setInterval(kAutoHideDelayMs);
    connect(&m_autoHideTimer, &QTimer::timeout, this, [this] {
        if (!m_locked && m_playing) setControlsVisible(false);
    });

    m_gestureFeedbackTimer.setSingleShot(true);
    m_gestureFeedbackTimer.setInterval(kGestureFeedbackDelayMs);
    connect(&m_gestureFeedbackTimer, &QTimer::timeout, this, &PlayerOverlayController::clearGestureFeedback);

    m_accumulatedSeekResetTimer.setSingleShot(true);
    m_accumulatedSeekResetTimer.setInterval(kDoubleTapSeekResetDelayMs);
    connect(&m_accumulatedSeekResetTimer, &QTimer::timeout, this, [this] {
        m_accumulatedSeekDirection = 0;
        m_accumulatedSeekBaselineMs = 0;
        m_accumulatedSeekAmountMs = 0;
    });
}

QString PlayerOverlayController::speedLabel() const
{
    if (std::abs(m_playbackSpeed - 1.0) < 0.01) return QStringLiteral("1x");
    return QString::number(m_playbackSpeed, 'g', 3) + QStringLiteral("x");
}

QString PlayerOverlayController::resizeModeLabel() const
{
    switch (m_resizeMode) {
    case 1: return QStringLiteral("Fill");
    case 2: return QStringLiteral("Zoom");
    default: return QStringLiteral("Fit");
    }
}

QString PlayerOverlayController::playerErrorMessage() const
{
    return !m_nativePlayerErrorMessage.isEmpty() ? m_nativePlayerErrorMessage : m_contextPlayerErrorMessage;
}

void PlayerOverlayController::setTitle(const QString &title)
{
    const auto clean = title.trimmed();
    if (clean.isEmpty() || m_title == clean) return;
    m_title = clean;
    emit metadataChanged();
}

void PlayerOverlayController::setStreamTitle(const QString &streamTitle)
{
    if (m_streamTitle == streamTitle) return;
    m_streamTitle = streamTitle;
    emit metadataChanged();
}

void PlayerOverlayController::setProviderName(const QString &providerName)
{
    if (m_providerName == providerName) return;
    m_providerName = providerName;
    emit metadataChanged();
}

void PlayerOverlayController::setEpisodeLabel(const QString &episodeLabel)
{
    if (m_episodeLabel == episodeLabel) return;
    m_episodeLabel = episodeLabel;
    emit metadataChanged();
}

void PlayerOverlayController::setControlsVisible(bool visible)
{
    if (m_controlsVisible == visible) {
        if (visible) restartAutoHideTimer();
        return;
    }
    m_controlsVisible = visible;
    emit controlsVisibleChanged();
    if (visible) restartAutoHideTimer();
}

void PlayerOverlayController::setLocked(bool locked)
{
    if (m_locked == locked) return;
    m_locked = locked;
    emit lockedChanged();
    if (locked) {
        setControlsVisible(false);
    } else {
        setControlsVisible(true);
    }
}

void PlayerOverlayController::setPlaybackSnapshot(
    qint64 positionMs,
    qint64 durationMs,
    bool playing,
    bool loading,
    double playbackSpeed,
    bool ended
)
{
    m_positionMs = qMax<qint64>(0, positionMs);
    m_durationMs = qMax<qint64>(0, durationMs);
    m_playing = playing;
    m_loading = loading;
    if (!m_loading) m_initialLoadCompleted = true;
    m_playbackSpeed = playbackSpeed <= 0.0 ? 1.0 : playbackSpeed;
    m_ended = ended;
    emit playbackChanged();
}

void PlayerOverlayController::setTracks(
    const QVariantList &audioTracks,
    const QVariantList &subtitleTracks,
    int selectedAudioIndex,
    int selectedSubtitleIndex
)
{
    m_audioTracks = audioTracks;
    m_subtitleTracks = subtitleTracks;
    m_selectedAudioIndex = selectedAudioIndex;
    m_selectedSubtitleIndex = selectedSubtitleIndex;
    emit tracksChanged();
}

void PlayerOverlayController::setPlayerContextJson(const QString &contextJson)
{
    const auto document = QJsonDocument::fromJson(contextJson.toUtf8());
    if (!document.isObject()) return;

    const auto context = document.object();
    const bool previousOpeningOverlayVisible = openingOverlayVisible();
    setTitle(context.value(QStringLiteral("title")).toString(m_title));
    m_logoUrl = context.value(QStringLiteral("logoUrl")).toString();
    m_artworkUrl = context.value(QStringLiteral("artworkUrl")).toString();
    setStreamTitle(context.value(QStringLiteral("streamTitle")).toString());
    setProviderName(context.value(QStringLiteral("providerName")).toString());
    setEpisodeLabel(context.value(QStringLiteral("episodeLabel")).toString());
    m_pauseDescription = context.value(QStringLiteral("pauseDescription")).toString();

    m_sources = jsonArrayToVariantList(context.value(QStringLiteral("sources")).toArray());
    m_sourceFilters = jsonArrayToVariantList(context.value(QStringLiteral("sourceFilters")).toArray());
    m_sourcePickerAvailable = context.value(QStringLiteral("sourcePickerAvailable")).toBool(!m_sources.isEmpty());
    m_episodes = jsonArrayToVariantList(context.value(QStringLiteral("episodes")).toArray());
    m_episodePickerAvailable = context.value(QStringLiteral("episodePickerAvailable")).toBool(!m_episodes.isEmpty());
    m_currentSeason = context.value(QStringLiteral("currentSeason")).toInt(-1);
    m_currentEpisode = context.value(QStringLiteral("currentEpisode")).toInt(-1);
    m_episodeStreams = jsonArrayToVariantList(context.value(QStringLiteral("episodeStreams")).toArray());
    m_episodeStreamFilters = jsonArrayToVariantList(context.value(QStringLiteral("episodeStreamFilters")).toArray());
    m_addonSubtitles = jsonArrayToVariantList(context.value(QStringLiteral("addonSubtitles")).toArray());
    m_sourcesLoading = context.value(QStringLiteral("sourcesLoading")).toBool(false);
    m_episodeStreamsLoading = context.value(QStringLiteral("episodeStreamsLoading")).toBool(false);
    m_addonSubtitlesLoading = context.value(QStringLiteral("addonSubtitlesLoading")).toBool(false);
    m_selectedEpisodeLabel = context.value(QStringLiteral("selectedEpisodeLabel")).toString();
    m_skipIntroLabel = context.value(QStringLiteral("skipIntroLabel")).toString();
    m_skipIntroAvailable = context.value(QStringLiteral("skipIntroAvailable")).toBool(false);
    m_skipIntroDismissed = context.value(QStringLiteral("skipIntroDismissed")).toBool(false);
    m_skipIntroKey = context.value(QStringLiteral("skipIntroKey")).toString();
    m_nextEpisodeLabel = context.value(QStringLiteral("nextEpisodeLabel")).toString();
    m_nextEpisodeAvailable = context.value(QStringLiteral("nextEpisodeAvailable")).toBool(false);
    m_nextEpisodePlayable = context.value(QStringLiteral("nextEpisodePlayable")).toBool(true);
    m_nextEpisodeThumbnail = context.value(QStringLiteral("nextEpisodeThumbnail")).toString();
    m_nextEpisodeAutoPlaySearching = context.value(QStringLiteral("nextEpisodeAutoPlaySearching")).toBool(false);
    m_nextEpisodeStatusText = buildNextEpisodeStatusText(context);
    m_parentalWarnings = jsonArrayToVariantList(context.value(QStringLiteral("parentalWarnings")).toArray());
    m_parentalGuideVisible = context.value(QStringLiteral("parentalGuideVisible")).toBool(false);
    const auto previousErrorMessage = playerErrorMessage();
    m_contextPlayerErrorMessage = context.value(QStringLiteral("playerErrorMessage")).toString().trimmed();
    emitPlayerErrorChangedIfNeeded(previousErrorMessage);
    m_submitIntroAvailable = context.value(QStringLiteral("submitIntroAvailable")).toBool(false);
    m_submitIntroVisible = context.value(QStringLiteral("submitIntroVisible")).toBool(false);
    m_submitIntroSubmitting = context.value(QStringLiteral("submitIntroSubmitting")).toBool(false);
    m_submitIntroSegmentType = context.value(QStringLiteral("submitIntroSegmentType")).toString(m_submitIntroSegmentType);
    m_submitIntroStartTime = context.value(QStringLiteral("submitIntroStartTime")).toString(m_submitIntroStartTime);
    m_submitIntroEndTime = context.value(QStringLiteral("submitIntroEndTime")).toString(m_submitIntroEndTime);
    m_showLoadingOverlay = context.value(QStringLiteral("showLoadingOverlay")).toBool(m_showLoadingOverlay);
    m_holdToSpeedEnabled = context.value(QStringLiteral("holdToSpeedEnabled")).toBool(m_holdToSpeedEnabled);
    m_holdToSpeedValue = qBound(1.0, context.value(QStringLiteral("holdToSpeedValue")).toDouble(m_holdToSpeedValue), 4.0);
    updateResizeMode(resizeModeFromContext(context, m_resizeMode), false, true);

    const auto style = context.value(QStringLiteral("subtitleStyle")).toObject();
    if (!style.isEmpty()) {
        updateSubtitleStyle(
            style.value(QStringLiteral("textColor")).toString(m_subtitleTextColor),
            style.value(QStringLiteral("outlineEnabled")).toBool(m_subtitleOutlineEnabled),
            style.value(QStringLiteral("fontSizeSp")).toInt(m_subtitleFontSizeSp),
            style.value(QStringLiteral("bottomOffset")).toInt(m_subtitleBottomOffset),
            false
        );
    }

    emit metadataChanged();
    emit playerContextChanged();
    if (previousOpeningOverlayVisible != openingOverlayVisible()) emit playbackChanged();
}

void PlayerOverlayController::setPlayerError(const QString &message)
{
    const auto clean = message.trimmed();
    if (clean.isEmpty() || m_nativePlayerErrorMessage == clean) return;
    const auto previousErrorMessage = playerErrorMessage();
    m_nativePlayerErrorMessage = clean;
    m_loading = false;
    m_playing = false;
    m_speedBoostRestoreSpeed = -1.0;
    m_initialLoadCompleted = true;
    setControlsVisible(false);
    emitPlayerErrorChangedIfNeeded(previousErrorMessage);
    emit playbackChanged();
}

void PlayerOverlayController::setFallbackTitleFromUrl(const QString &url)
{
    setTitle(displayNameFromUrl(url));
}

void PlayerOverlayController::resetForPlayback()
{
    m_positionMs = 0;
    m_durationMs = 0;
    m_loading = true;
    m_ended = false;
    m_playing = false;
    m_speedBoostRestoreSpeed = -1.0;
    m_initialLoadCompleted = false;
    if (!playerErrorMessage().isEmpty()) {
        m_contextPlayerErrorMessage.clear();
        m_nativePlayerErrorMessage.clear();
        emit playerErrorChanged();
    }
    clearGestureFeedback();
    m_accumulatedSeekResetTimer.stop();
    m_accumulatedSeekDirection = 0;
    m_accumulatedSeekBaselineMs = 0;
    m_accumulatedSeekAmountMs = 0;
    setControlsVisible(true);
    emit playbackChanged();
}

void PlayerOverlayController::revealControls()
{
    if (m_locked) return;
    setControlsVisible(true);
}

void PlayerOverlayController::toggleControls()
{
    if (m_locked) return;
    setControlsVisible(!m_controlsVisible);
}

void PlayerOverlayController::togglePlayback()
{
    if (m_playing) {
        emit pauseRequested();
    } else {
        if (m_ended) emit seekToRequested(0);
        emit resumeRequested();
    }
    setControlsVisible(true);
}

void PlayerOverlayController::seekTo(double positionMs)
{
    emit seekToRequested(qMax<qint64>(0, qRound64(positionMs)));
    setControlsVisible(true);
}

void PlayerOverlayController::seekBy(double offsetMs)
{
    const auto cleanOffset = qRound64(offsetMs);
    emit seekByRequested(cleanOffset);
    if (cleanOffset != 0) {
        const auto seconds = qMax<qint64>(1, qAbs(cleanOffset) / 1000);
        showGestureFeedback(
            QStringLiteral("%1%2s").arg(cleanOffset > 0 ? QStringLiteral("+") : QStringLiteral("-")).arg(seconds),
            cleanOffset > 0 ? QStringLiteral("seekForward") : QStringLiteral("seekBackward")
        );
    }
    setControlsVisible(true);
}

void PlayerOverlayController::handleDoubleTapSeek(bool forward)
{
    const int direction = forward ? 1 : -1;
    if (m_accumulatedSeekDirection == direction) {
        m_accumulatedSeekAmountMs += kDoubleTapSeekStepMs;
    } else {
        m_accumulatedSeekDirection = direction;
        m_accumulatedSeekBaselineMs = qMax<qint64>(0, m_positionMs);
        m_accumulatedSeekAmountMs = kDoubleTapSeekStepMs;
    }

    auto targetPosition = m_accumulatedSeekBaselineMs + (direction * m_accumulatedSeekAmountMs);
    targetPosition = qMax<qint64>(0, targetPosition);
    if (m_durationMs > 0) targetPosition = qMin(targetPosition, m_durationMs);

    emit seekToRequested(targetPosition);
    const auto seconds = qMax<qint64>(1, m_accumulatedSeekAmountMs / 1000);
    showGestureFeedback(
        QStringLiteral("%1%2s").arg(forward ? QStringLiteral("+") : QStringLiteral("-")).arg(seconds),
        forward ? QStringLiteral("seekForward") : QStringLiteral("seekBackward")
    );
    m_accumulatedSeekResetTimer.start();
    setControlsVisible(true);
}

void PlayerOverlayController::previewHorizontalSeek(double targetPositionMs, double baselinePositionMs)
{
    auto targetPosition = qMax<qint64>(0, qRound64(targetPositionMs));
    if (m_durationMs > 0) targetPosition = qMin(targetPosition, m_durationMs);

    const auto baselinePosition = qMax<qint64>(0, qRound64(baselinePositionMs));
    const auto deltaMs = targetPosition - baselinePosition;
    const bool forward = deltaMs >= 0;
    const auto deltaSeconds = qMax<qint64>(0, qRound64(qAbs(deltaMs) / 1000.0));
    showGestureFeedback(
        formatPlayerTime(targetPosition),
        forward ? QStringLiteral("seekForward") : QStringLiteral("seekBackward"),
        QStringLiteral("%1%2s").arg(forward ? QStringLiteral("+") : QStringLiteral("-")).arg(deltaSeconds)
    );
}

void PlayerOverlayController::commitHorizontalSeek(double targetPositionMs)
{
    auto targetPosition = qMax<qint64>(0, qRound64(targetPositionMs));
    if (m_durationMs > 0) targetPosition = qMin(targetPosition, m_durationMs);
    emit seekToRequested(targetPosition);
    clearGestureFeedback();
    setControlsVisible(true);
}

void PlayerOverlayController::clearGestureFeedbackNow()
{
    clearGestureFeedback();
}

bool PlayerOverlayController::beginHoldToSpeed()
{
    if (!m_holdToSpeedEnabled) return false;
    if (m_speedBoostRestoreSpeed >= 0.0) return false;
    if (qAbs(m_playbackSpeed - m_holdToSpeedValue) < 0.01) return false;

    m_speedBoostRestoreSpeed = m_playbackSpeed;
    emit playbackSpeedRequested(m_holdToSpeedValue);
    showGestureFeedback(QString::number(m_holdToSpeedValue, 'g', 3) + QStringLiteral("x"), QStringLiteral("speed"));
    setControlsVisible(false);
    return true;
}

void PlayerOverlayController::endHoldToSpeed()
{
    if (m_speedBoostRestoreSpeed < 0.0) return;
    const auto restoreSpeed = m_speedBoostRestoreSpeed;
    m_speedBoostRestoreSpeed = -1.0;
    emit playbackSpeedRequested(restoreSpeed);
    clearGestureFeedback();
}

void PlayerOverlayController::setGestureVolume(double fraction)
{
    const auto clean = qBound(0.0, fraction, 1.0);
    if (qAbs(m_volumeFraction - clean) > 0.001) {
        m_volumeFraction = clean;
        emit volumeChanged();
        emit volumeRequested(clean);
    }
    const auto percent = qRound(clean * 100.0);
    showGestureFeedback(
        percent <= 0 ? QStringLiteral("Muted") : QStringLiteral("Volume %1%").arg(percent),
        percent <= 0 ? QStringLiteral("volumeMuted") : QStringLiteral("volume"),
        QString(),
        percent <= 0
    );
}

void PlayerOverlayController::setGestureBrightness(double fraction)
{
    const auto clean = qBound(0.0, fraction, 1.0);
    if (qAbs(m_brightnessFraction - clean) > 0.001) {
        m_brightnessFraction = clean;
        emit brightnessChanged();
        emit brightnessRequested(clean);
    }
    showGestureFeedback(QStringLiteral("Brightness %1%").arg(qRound(clean * 100.0)), QStringLiteral("brightness"));
}

void PlayerOverlayController::cyclePlaybackSpeed()
{
    const QList<double> speeds{1.0, 1.25, 1.5, 2.0};
    double next = speeds.first();
    for (const auto speed : speeds) {
        if (speed > m_playbackSpeed + 0.01) {
            next = speed;
            break;
        }
    }
    emit playbackSpeedRequested(next);
    showGestureFeedback(QString::number(next, 'g', 3) + QStringLiteral("x"), QStringLiteral("speed"));
    setControlsVisible(true);
}

void PlayerOverlayController::cycleResizeMode()
{
    updateResizeMode((m_resizeMode + 1) % 3, true, true);
    showGestureFeedback(resizeModeLabel(), QStringLiteral("resize"));
    setControlsVisible(true);
}

void PlayerOverlayController::toggleLock()
{
    setLocked(!m_locked);
}

void PlayerOverlayController::closePlayer()
{
    emit playerActionRequested(QStringLiteral("closePlayer"));
    emit stopRequested();
}

void PlayerOverlayController::dismissPlayerError()
{
    if (!playerErrorVisible()) return;
    m_contextPlayerErrorMessage.clear();
    m_nativePlayerErrorMessage.clear();
    emit playerErrorChanged();
}

void PlayerOverlayController::refreshTracks()
{
    emit trackRefreshRequested();
}

void PlayerOverlayController::selectAudioTrack(int index)
{
    emit audioTrackRequested(index);
    setControlsVisible(true);
}

void PlayerOverlayController::selectSubtitleTrack(int index)
{
    emit subtitleTrackRequested(index);
    emit playerActionRequested(QStringLiteral("selectBuiltInSubtitle:%1").arg(index));
    setControlsVisible(true);
}

void PlayerOverlayController::fetchAddonSubtitles()
{
    emit playerActionRequested(QStringLiteral("fetchAddonSubtitles"));
}

void PlayerOverlayController::selectAddonSubtitle(int index)
{
    const auto row = m_addonSubtitles.value(index).toMap();
    const auto url = row.value(QStringLiteral("url")).toString();
    if (!url.isEmpty()) emit externalSubtitleRequested(url);
    emit playerActionRequested(QStringLiteral("selectAddonSubtitle:%1").arg(index));
    setControlsVisible(true);
}

void PlayerOverlayController::openSourcesPanel()
{
    emit playerActionRequested(QStringLiteral("openSources"));
    setControlsVisible(true);
}

void PlayerOverlayController::reloadSources()
{
    emit playerActionRequested(QStringLiteral("reloadSources"));
}

void PlayerOverlayController::selectSourceFilter(int index)
{
    emit playerActionRequested(QStringLiteral("selectSourceFilter:%1").arg(index));
}

void PlayerOverlayController::selectSource(int index)
{
    emit playerActionRequested(QStringLiteral("selectSource:%1").arg(index));
}

void PlayerOverlayController::openEpisodesPanel()
{
    emit playerActionRequested(QStringLiteral("openEpisodes"));
    setControlsVisible(true);
}

void PlayerOverlayController::selectEpisode(int index)
{
    emit playerActionRequested(QStringLiteral("selectEpisode:%1").arg(index));
}

void PlayerOverlayController::selectEpisodeStreamFilter(int index)
{
    emit playerActionRequested(QStringLiteral("selectEpisodeStreamFilter:%1").arg(index));
}

void PlayerOverlayController::selectEpisodeStream(int index)
{
    emit playerActionRequested(QStringLiteral("selectEpisodeStream:%1").arg(index));
}

void PlayerOverlayController::backToEpisodes()
{
    emit playerActionRequested(QStringLiteral("backToEpisodes"));
}

void PlayerOverlayController::reloadEpisodeStreams()
{
    emit playerActionRequested(QStringLiteral("reloadEpisodeStreams"));
}

void PlayerOverlayController::skipIntro()
{
    emit playerActionRequested(QStringLiteral("skipIntro"));
}

void PlayerOverlayController::playNextEpisode()
{
    emit playerActionRequested(QStringLiteral("playNextEpisode"));
}

void PlayerOverlayController::dismissNextEpisode()
{
    emit playerActionRequested(QStringLiteral("dismissNextEpisode"));
}

void PlayerOverlayController::dismissParentalGuide()
{
    emit playerActionRequested(QStringLiteral("dismissParentalGuide"));
}

void PlayerOverlayController::openSubmitIntroDialog()
{
    if (!m_submitIntroAvailable) return;
    m_submitIntroVisible = true;
    emit playerContextChanged();
    emit playerActionRequested(QStringLiteral("openSubmitIntro"));
    setControlsVisible(true);
}

void PlayerOverlayController::dismissSubmitIntroDialog()
{
    m_submitIntroVisible = false;
    emit playerContextChanged();
    emit playerActionRequested(QStringLiteral("dismissSubmitIntro"));
}

void PlayerOverlayController::setSubmitIntroSegmentType(const QString &segmentType)
{
    const auto clean = segmentType == QLatin1String("recap") || segmentType == QLatin1String("outro")
        ? segmentType
        : QStringLiteral("intro");
    m_submitIntroSegmentType = clean;
    emit playerContextChanged();
    emit playerActionRequested(QStringLiteral("setSubmitIntroSegmentType:%1").arg(clean));
}

void PlayerOverlayController::setSubmitIntroStartTime(const QString &startTime)
{
    m_submitIntroStartTime = startTime.trimmed();
    emit playerContextChanged();
    emit playerActionRequested(QStringLiteral("setSubmitIntroStartTime:%1").arg(m_submitIntroStartTime));
}

void PlayerOverlayController::setSubmitIntroEndTime(const QString &endTime)
{
    m_submitIntroEndTime = endTime.trimmed();
    emit playerContextChanged();
    emit playerActionRequested(QStringLiteral("setSubmitIntroEndTime:%1").arg(m_submitIntroEndTime));
}

void PlayerOverlayController::submitIntroTimestamps()
{
    if (!m_submitIntroAvailable || m_submitIntroSubmitting) return;
    emit playerActionRequested(QStringLiteral("submitIntro"));
}

void PlayerOverlayController::setSubtitleTextColor(const QString &color)
{
    updateSubtitleStyle(color, m_subtitleOutlineEnabled, m_subtitleFontSizeSp, m_subtitleBottomOffset, true);
}

void PlayerOverlayController::setSubtitleOutlineEnabled(bool enabled)
{
    updateSubtitleStyle(m_subtitleTextColor, enabled, m_subtitleFontSizeSp, m_subtitleBottomOffset, true);
}

void PlayerOverlayController::setSubtitleFontSizeSp(int fontSizeSp)
{
    updateSubtitleStyle(m_subtitleTextColor, m_subtitleOutlineEnabled, fontSizeSp, m_subtitleBottomOffset, true);
}

void PlayerOverlayController::setSubtitleBottomOffset(int bottomOffset)
{
    updateSubtitleStyle(m_subtitleTextColor, m_subtitleOutlineEnabled, m_subtitleFontSizeSp, bottomOffset, true);
}

void PlayerOverlayController::resetSubtitleStyle()
{
    updateSubtitleStyle(QStringLiteral("#FFFFFF"), false, 18, 20, true);
}

void PlayerOverlayController::restartAutoHideTimer()
{
    if (!m_locked && m_playing) {
        m_autoHideTimer.start();
    } else {
        m_autoHideTimer.stop();
    }
}

void PlayerOverlayController::emitPlayerErrorChangedIfNeeded(const QString &previousMessage)
{
    if (previousMessage != playerErrorMessage()) emit playerErrorChanged();
}

void PlayerOverlayController::showGestureFeedback(const QString &message, const QString &icon, const QString &secondaryMessage, bool danger)
{
    m_gestureFeedbackMessage = message;
    m_gestureFeedbackIcon = icon;
    m_gestureFeedbackSecondaryMessage = secondaryMessage;
    m_gestureFeedbackDanger = danger;
    m_gestureFeedbackVisible = true;
    emit gestureFeedbackChanged();
    m_gestureFeedbackTimer.start();
}

void PlayerOverlayController::clearGestureFeedback()
{
    if (!m_gestureFeedbackVisible &&
        m_gestureFeedbackMessage.isEmpty() &&
        m_gestureFeedbackSecondaryMessage.isEmpty() &&
        !m_gestureFeedbackDanger) {
        return;
    }
    m_gestureFeedbackTimer.stop();
    m_gestureFeedbackVisible = false;
    m_gestureFeedbackMessage.clear();
    m_gestureFeedbackSecondaryMessage.clear();
    m_gestureFeedbackDanger = false;
    emit gestureFeedbackChanged();
}

void PlayerOverlayController::updateSubtitleStyle(
    const QString &textColor,
    bool outlineEnabled,
    int fontSizeSp,
    int bottomOffset,
    bool persistToCompose
)
{
    const auto cleanColor = normalizeColor(textColor);
    const auto cleanFontSize = qBound(12, fontSizeSp, 40);
    const auto cleanBottomOffset = qBound(0, bottomOffset, 200);
    const bool changed = !m_subtitleStyleInitialized ||
        m_subtitleTextColor != cleanColor ||
        m_subtitleOutlineEnabled != outlineEnabled ||
        m_subtitleFontSizeSp != cleanFontSize ||
        m_subtitleBottomOffset != cleanBottomOffset;

    m_subtitleStyleInitialized = true;
    m_subtitleTextColor = cleanColor;
    m_subtitleOutlineEnabled = outlineEnabled;
    m_subtitleFontSizeSp = cleanFontSize;
    m_subtitleBottomOffset = cleanBottomOffset;

    if (changed) {
        emit subtitleStyleChanged();
        emitSubtitleStyleRequest();
    }

    if (persistToCompose) {
        emit playerActionRequested(
            QStringLiteral("setSubtitleStyle:%1,%2,%3,%4")
                .arg(m_subtitleTextColor)
                .arg(m_subtitleOutlineEnabled ? 1 : 0)
                .arg(m_subtitleFontSizeSp)
                .arg(m_subtitleBottomOffset)
        );
    }
}

void PlayerOverlayController::emitSubtitleStyleRequest()
{
    emit subtitleStyleRequested(m_subtitleTextColor, m_subtitleOutlineEnabled, m_subtitleFontSizeSp, m_subtitleBottomOffset);
}

void PlayerOverlayController::updateResizeMode(int mode, bool persistToCompose, bool applyToPlayer)
{
    const auto cleanMode = qBound(0, mode, 2);
    const bool changed = m_resizeMode != cleanMode;
    m_resizeMode = cleanMode;

    if (changed) emit resizeModeChanged();
    if (applyToPlayer && changed) emit resizeModeRequested(cleanMode);
    if (persistToCompose) {
        emit playerActionRequested(QStringLiteral("setResizeMode:%1").arg(cleanMode));
    }
}
