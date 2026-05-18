#pragma once

#include <QObject>
#include <QTimer>
#include <QVariantList>

class PlayerOverlayController final : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QString title READ title WRITE setTitle NOTIFY metadataChanged)
    Q_PROPERTY(QString logoUrl READ logoUrl NOTIFY metadataChanged)
    Q_PROPERTY(QString artworkUrl READ artworkUrl NOTIFY metadataChanged)
    Q_PROPERTY(QString streamTitle READ streamTitle WRITE setStreamTitle NOTIFY metadataChanged)
    Q_PROPERTY(QString providerName READ providerName WRITE setProviderName NOTIFY metadataChanged)
    Q_PROPERTY(QString episodeLabel READ episodeLabel WRITE setEpisodeLabel NOTIFY metadataChanged)
    Q_PROPERTY(QString pauseDescription READ pauseDescription NOTIFY metadataChanged)
    Q_PROPERTY(qint64 positionMs READ positionMs NOTIFY playbackChanged)
    Q_PROPERTY(qint64 durationMs READ durationMs NOTIFY playbackChanged)
    Q_PROPERTY(bool playing READ isPlaying NOTIFY playbackChanged)
    Q_PROPERTY(bool loading READ isLoading NOTIFY playbackChanged)
    Q_PROPERTY(bool ended READ isEnded NOTIFY playbackChanged)
    Q_PROPERTY(bool openingOverlayVisible READ openingOverlayVisible NOTIFY playbackChanged)
    Q_PROPERTY(double playbackSpeed READ playbackSpeed NOTIFY playbackChanged)
    Q_PROPERTY(bool holdToSpeedEnabled READ holdToSpeedEnabled NOTIFY playerContextChanged)
    Q_PROPERTY(double holdToSpeedValue READ holdToSpeedValue NOTIFY playerContextChanged)
    Q_PROPERTY(QString speedLabel READ speedLabel NOTIFY playbackChanged)
    Q_PROPERTY(QString resizeModeLabel READ resizeModeLabel NOTIFY resizeModeChanged)
    Q_PROPERTY(bool controlsVisible READ controlsVisible WRITE setControlsVisible NOTIFY controlsVisibleChanged)
    Q_PROPERTY(bool locked READ isLocked WRITE setLocked NOTIFY lockedChanged)
    Q_PROPERTY(QVariantList audioTracks READ audioTracks NOTIFY tracksChanged)
    Q_PROPERTY(QVariantList subtitleTracks READ subtitleTracks NOTIFY tracksChanged)
    Q_PROPERTY(int selectedAudioIndex READ selectedAudioIndex NOTIFY tracksChanged)
    Q_PROPERTY(int selectedSubtitleIndex READ selectedSubtitleIndex NOTIFY tracksChanged)
    Q_PROPERTY(QVariantList sources READ sources NOTIFY playerContextChanged)
    Q_PROPERTY(QVariantList sourceFilters READ sourceFilters NOTIFY playerContextChanged)
    Q_PROPERTY(bool sourcePickerAvailable READ sourcePickerAvailable NOTIFY playerContextChanged)
    Q_PROPERTY(QVariantList episodes READ episodes NOTIFY playerContextChanged)
    Q_PROPERTY(bool episodePickerAvailable READ episodePickerAvailable NOTIFY playerContextChanged)
    Q_PROPERTY(int currentSeason READ currentSeason NOTIFY playerContextChanged)
    Q_PROPERTY(int currentEpisode READ currentEpisode NOTIFY playerContextChanged)
    Q_PROPERTY(QVariantList episodeStreams READ episodeStreams NOTIFY playerContextChanged)
    Q_PROPERTY(QVariantList episodeStreamFilters READ episodeStreamFilters NOTIFY playerContextChanged)
    Q_PROPERTY(QVariantList addonSubtitles READ addonSubtitles NOTIFY playerContextChanged)
    Q_PROPERTY(bool sourcesLoading READ sourcesLoading NOTIFY playerContextChanged)
    Q_PROPERTY(bool episodeStreamsLoading READ episodeStreamsLoading NOTIFY playerContextChanged)
    Q_PROPERTY(bool addonSubtitlesLoading READ addonSubtitlesLoading NOTIFY playerContextChanged)
    Q_PROPERTY(QString selectedEpisodeLabel READ selectedEpisodeLabel NOTIFY playerContextChanged)
    Q_PROPERTY(QString skipIntroLabel READ skipIntroLabel NOTIFY playerContextChanged)
    Q_PROPERTY(bool skipIntroAvailable READ skipIntroAvailable NOTIFY playerContextChanged)
    Q_PROPERTY(bool skipIntroDismissed READ skipIntroDismissed NOTIFY playerContextChanged)
    Q_PROPERTY(QString skipIntroKey READ skipIntroKey NOTIFY playerContextChanged)
    Q_PROPERTY(QString nextEpisodeLabel READ nextEpisodeLabel NOTIFY playerContextChanged)
    Q_PROPERTY(bool nextEpisodeAvailable READ nextEpisodeAvailable NOTIFY playerContextChanged)
    Q_PROPERTY(bool nextEpisodePlayable READ nextEpisodePlayable NOTIFY playerContextChanged)
    Q_PROPERTY(QString nextEpisodeThumbnail READ nextEpisodeThumbnail NOTIFY playerContextChanged)
    Q_PROPERTY(QString nextEpisodeStatusText READ nextEpisodeStatusText NOTIFY playerContextChanged)
    Q_PROPERTY(bool nextEpisodeAutoPlaySearching READ nextEpisodeAutoPlaySearching NOTIFY playerContextChanged)
    Q_PROPERTY(QVariantList parentalWarnings READ parentalWarnings NOTIFY playerContextChanged)
    Q_PROPERTY(bool parentalGuideVisible READ parentalGuideVisible NOTIFY playerContextChanged)
    Q_PROPERTY(bool submitIntroAvailable READ submitIntroAvailable NOTIFY playerContextChanged)
    Q_PROPERTY(bool submitIntroVisible READ submitIntroVisible NOTIFY playerContextChanged)
    Q_PROPERTY(bool submitIntroSubmitting READ submitIntroSubmitting NOTIFY playerContextChanged)
    Q_PROPERTY(QString submitIntroSegmentType READ submitIntroSegmentType NOTIFY playerContextChanged)
    Q_PROPERTY(QString submitIntroStartTime READ submitIntroStartTime NOTIFY playerContextChanged)
    Q_PROPERTY(QString submitIntroEndTime READ submitIntroEndTime NOTIFY playerContextChanged)
    Q_PROPERTY(QString subtitleTextColor READ subtitleTextColor NOTIFY subtitleStyleChanged)
    Q_PROPERTY(bool subtitleOutlineEnabled READ subtitleOutlineEnabled NOTIFY subtitleStyleChanged)
    Q_PROPERTY(int subtitleFontSizeSp READ subtitleFontSizeSp NOTIFY subtitleStyleChanged)
    Q_PROPERTY(int subtitleBottomOffset READ subtitleBottomOffset NOTIFY subtitleStyleChanged)
    Q_PROPERTY(bool gestureFeedbackVisible READ gestureFeedbackVisible NOTIFY gestureFeedbackChanged)
    Q_PROPERTY(QString gestureFeedbackMessage READ gestureFeedbackMessage NOTIFY gestureFeedbackChanged)
    Q_PROPERTY(QString gestureFeedbackSecondaryMessage READ gestureFeedbackSecondaryMessage NOTIFY gestureFeedbackChanged)
    Q_PROPERTY(QString gestureFeedbackIcon READ gestureFeedbackIcon NOTIFY gestureFeedbackChanged)
    Q_PROPERTY(bool gestureFeedbackDanger READ gestureFeedbackDanger NOTIFY gestureFeedbackChanged)
    Q_PROPERTY(bool playerErrorVisible READ playerErrorVisible NOTIFY playerErrorChanged)
    Q_PROPERTY(QString playerErrorMessage READ playerErrorMessage NOTIFY playerErrorChanged)

public:
    explicit PlayerOverlayController(QObject *parent = nullptr);

    QString title() const { return m_title; }
    QString logoUrl() const { return m_logoUrl; }
    QString artworkUrl() const { return m_artworkUrl; }
    QString streamTitle() const { return m_streamTitle; }
    QString providerName() const { return m_providerName; }
    QString episodeLabel() const { return m_episodeLabel; }
    QString pauseDescription() const { return m_pauseDescription; }
    qint64 positionMs() const { return m_positionMs; }
    qint64 durationMs() const { return m_durationMs; }
    bool isPlaying() const { return m_playing; }
    bool isLoading() const { return m_loading; }
    bool isEnded() const { return m_ended; }
    bool openingOverlayVisible() const { return m_showLoadingOverlay && m_loading && !m_initialLoadCompleted; }
    double playbackSpeed() const { return m_playbackSpeed; }
    bool holdToSpeedEnabled() const { return m_holdToSpeedEnabled; }
    double holdToSpeedValue() const { return m_holdToSpeedValue; }
    QString speedLabel() const;
    QString resizeModeLabel() const;
    bool controlsVisible() const { return m_controlsVisible; }
    bool isLocked() const { return m_locked; }
    QVariantList audioTracks() const { return m_audioTracks; }
    QVariantList subtitleTracks() const { return m_subtitleTracks; }
    int selectedAudioIndex() const { return m_selectedAudioIndex; }
    int selectedSubtitleIndex() const { return m_selectedSubtitleIndex; }
    QVariantList sources() const { return m_sources; }
    QVariantList sourceFilters() const { return m_sourceFilters; }
    bool sourcePickerAvailable() const { return m_sourcePickerAvailable; }
    QVariantList episodes() const { return m_episodes; }
    bool episodePickerAvailable() const { return m_episodePickerAvailable; }
    int currentSeason() const { return m_currentSeason; }
    int currentEpisode() const { return m_currentEpisode; }
    QVariantList episodeStreams() const { return m_episodeStreams; }
    QVariantList episodeStreamFilters() const { return m_episodeStreamFilters; }
    QVariantList addonSubtitles() const { return m_addonSubtitles; }
    bool sourcesLoading() const { return m_sourcesLoading; }
    bool episodeStreamsLoading() const { return m_episodeStreamsLoading; }
    bool addonSubtitlesLoading() const { return m_addonSubtitlesLoading; }
    QString selectedEpisodeLabel() const { return m_selectedEpisodeLabel; }
    QString skipIntroLabel() const { return m_skipIntroLabel; }
    bool skipIntroAvailable() const { return m_skipIntroAvailable; }
    bool skipIntroDismissed() const { return m_skipIntroDismissed; }
    QString skipIntroKey() const { return m_skipIntroKey; }
    QString nextEpisodeLabel() const { return m_nextEpisodeLabel; }
    bool nextEpisodeAvailable() const { return m_nextEpisodeAvailable; }
    bool nextEpisodePlayable() const { return m_nextEpisodePlayable; }
    QString nextEpisodeThumbnail() const { return m_nextEpisodeThumbnail; }
    QString nextEpisodeStatusText() const { return m_nextEpisodeStatusText; }
    bool nextEpisodeAutoPlaySearching() const { return m_nextEpisodeAutoPlaySearching; }
    QVariantList parentalWarnings() const { return m_parentalWarnings; }
    bool parentalGuideVisible() const { return m_parentalGuideVisible; }
    bool submitIntroAvailable() const { return m_submitIntroAvailable; }
    bool submitIntroVisible() const { return m_submitIntroVisible; }
    bool submitIntroSubmitting() const { return m_submitIntroSubmitting; }
    QString submitIntroSegmentType() const { return m_submitIntroSegmentType; }
    QString submitIntroStartTime() const { return m_submitIntroStartTime; }
    QString submitIntroEndTime() const { return m_submitIntroEndTime; }
    QString subtitleTextColor() const { return m_subtitleTextColor; }
    bool subtitleOutlineEnabled() const { return m_subtitleOutlineEnabled; }
    int subtitleFontSizeSp() const { return m_subtitleFontSizeSp; }
    int subtitleBottomOffset() const { return m_subtitleBottomOffset; }
    bool gestureFeedbackVisible() const { return m_gestureFeedbackVisible; }
    QString gestureFeedbackMessage() const { return m_gestureFeedbackMessage; }
    QString gestureFeedbackSecondaryMessage() const { return m_gestureFeedbackSecondaryMessage; }
    QString gestureFeedbackIcon() const { return m_gestureFeedbackIcon; }
    bool gestureFeedbackDanger() const { return m_gestureFeedbackDanger; }
    bool playerErrorVisible() const { return !playerErrorMessage().isEmpty(); }
    QString playerErrorMessage() const;

    void setTitle(const QString &title);
    void setStreamTitle(const QString &streamTitle);
    void setProviderName(const QString &providerName);
    void setEpisodeLabel(const QString &episodeLabel);
    void setControlsVisible(bool visible);
    void setLocked(bool locked);

public slots:
    void setPlaybackSnapshot(qint64 positionMs, qint64 durationMs, bool playing, bool loading, double playbackSpeed, bool ended);
    void setTracks(const QVariantList &audioTracks, const QVariantList &subtitleTracks, int selectedAudioIndex, int selectedSubtitleIndex);
    void setPlayerContextJson(const QString &contextJson);
    void setPlayerError(const QString &message);
    void resetForPlayback();

    Q_INVOKABLE void revealControls();
    Q_INVOKABLE void toggleControls();
    Q_INVOKABLE void togglePlayback();
    Q_INVOKABLE void seekTo(double positionMs);
    Q_INVOKABLE void seekBy(double offsetMs);
    Q_INVOKABLE void handleDoubleTapSeek(bool forward);
    Q_INVOKABLE void previewHorizontalSeek(double targetPositionMs, double baselinePositionMs);
    Q_INVOKABLE void commitHorizontalSeek(double targetPositionMs);
    Q_INVOKABLE void clearGestureFeedbackNow();
    Q_INVOKABLE bool beginHoldToSpeed();
    Q_INVOKABLE void endHoldToSpeed();
    Q_INVOKABLE void cyclePlaybackSpeed();
    Q_INVOKABLE void cycleResizeMode();
    Q_INVOKABLE void toggleLock();
    Q_INVOKABLE void closePlayer();
    Q_INVOKABLE void dismissPlayerError();
    Q_INVOKABLE void refreshTracks();
    Q_INVOKABLE void selectAudioTrack(int index);
    Q_INVOKABLE void selectSubtitleTrack(int index);
    Q_INVOKABLE void fetchAddonSubtitles();
    Q_INVOKABLE void selectAddonSubtitle(int index);
    Q_INVOKABLE void openSourcesPanel();
    Q_INVOKABLE void reloadSources();
    Q_INVOKABLE void selectSourceFilter(int index);
    Q_INVOKABLE void selectSource(int index);
    Q_INVOKABLE void openEpisodesPanel();
    Q_INVOKABLE void selectEpisode(int index);
    Q_INVOKABLE void selectEpisodeStreamFilter(int index);
    Q_INVOKABLE void selectEpisodeStream(int index);
    Q_INVOKABLE void backToEpisodes();
    Q_INVOKABLE void reloadEpisodeStreams();
    Q_INVOKABLE void skipIntro();
    Q_INVOKABLE void playNextEpisode();
    Q_INVOKABLE void dismissNextEpisode();
    Q_INVOKABLE void dismissParentalGuide();
    Q_INVOKABLE void openSubmitIntroDialog();
    Q_INVOKABLE void dismissSubmitIntroDialog();
    Q_INVOKABLE void setSubmitIntroSegmentType(const QString &segmentType);
    Q_INVOKABLE void setSubmitIntroStartTime(const QString &startTime);
    Q_INVOKABLE void setSubmitIntroEndTime(const QString &endTime);
    Q_INVOKABLE void submitIntroTimestamps();
    Q_INVOKABLE void setSubtitleTextColor(const QString &color);
    Q_INVOKABLE void setSubtitleOutlineEnabled(bool enabled);
    Q_INVOKABLE void setSubtitleFontSizeSp(int fontSizeSp);
    Q_INVOKABLE void setSubtitleBottomOffset(int bottomOffset);
    Q_INVOKABLE void resetSubtitleStyle();

signals:
    void metadataChanged();
    void playbackChanged();
    void resizeModeChanged();
    void controlsVisibleChanged();
    void lockedChanged();
    void tracksChanged();
    void playerContextChanged();
    void subtitleStyleChanged();
    void gestureFeedbackChanged();
    void playerErrorChanged();

    void pauseRequested();
    void resumeRequested();
    void stopRequested();
    void seekToRequested(qint64 positionMs);
    void seekByRequested(qint64 offsetMs);
    void playbackSpeedRequested(double speed);
    void resizeModeRequested(int mode);
    void audioTrackRequested(int index);
    void subtitleTrackRequested(int index);
    void externalSubtitleRequested(const QString &url);
    void subtitleStyleRequested(const QString &textColor, bool outlineEnabled, int fontSizeSp, int bottomOffset);
    void trackRefreshRequested();
    void playerActionRequested(const QString &action);

private:
    void restartAutoHideTimer();
    void emitPlayerErrorChangedIfNeeded(const QString &previousMessage);
    void showGestureFeedback(const QString &message, const QString &icon, const QString &secondaryMessage = QString(), bool danger = false);
    void clearGestureFeedback();
    void updateSubtitleStyle(const QString &textColor, bool outlineEnabled, int fontSizeSp, int bottomOffset, bool persistToCompose);
    void emitSubtitleStyleRequest();
    void updateResizeMode(int mode, bool persistToCompose, bool applyToPlayer);

    QString m_title = QStringLiteral("Nuvio");
    QString m_logoUrl;
    QString m_artworkUrl;
    QString m_streamTitle;
    QString m_providerName;
    QString m_episodeLabel;
    QString m_pauseDescription;
    qint64 m_positionMs = 0;
    qint64 m_durationMs = 0;
    bool m_playing = false;
    bool m_loading = false;
    bool m_ended = false;
    bool m_initialLoadCompleted = false;
    bool m_showLoadingOverlay = true;
    double m_playbackSpeed = 1.0;
    bool m_holdToSpeedEnabled = true;
    double m_holdToSpeedValue = 2.0;
    double m_speedBoostRestoreSpeed = -1.0;
    int m_resizeMode = 0;
    bool m_controlsVisible = true;
    bool m_locked = false;
    QVariantList m_audioTracks;
    QVariantList m_subtitleTracks;
    int m_selectedAudioIndex = -1;
    int m_selectedSubtitleIndex = -1;
    QVariantList m_sources;
    QVariantList m_sourceFilters;
    bool m_sourcePickerAvailable = false;
    QVariantList m_episodes;
    bool m_episodePickerAvailable = false;
    int m_currentSeason = -1;
    int m_currentEpisode = -1;
    QVariantList m_episodeStreams;
    QVariantList m_episodeStreamFilters;
    QVariantList m_addonSubtitles;
    bool m_sourcesLoading = false;
    bool m_episodeStreamsLoading = false;
    bool m_addonSubtitlesLoading = false;
    QString m_selectedEpisodeLabel;
    QString m_skipIntroLabel;
    bool m_skipIntroAvailable = false;
    bool m_skipIntroDismissed = false;
    QString m_skipIntroKey;
    QString m_nextEpisodeLabel;
    bool m_nextEpisodeAvailable = false;
    bool m_nextEpisodePlayable = true;
    QString m_nextEpisodeThumbnail;
    QString m_nextEpisodeStatusText;
    bool m_nextEpisodeAutoPlaySearching = false;
    QVariantList m_parentalWarnings;
    bool m_parentalGuideVisible = false;
    bool m_submitIntroAvailable = false;
    bool m_submitIntroVisible = false;
    bool m_submitIntroSubmitting = false;
    QString m_submitIntroSegmentType = QStringLiteral("intro");
    QString m_submitIntroStartTime = QStringLiteral("00:00");
    QString m_submitIntroEndTime = QStringLiteral("00:00");
    QString m_subtitleTextColor = QStringLiteral("#FFFFFF");
    bool m_subtitleOutlineEnabled = false;
    int m_subtitleFontSizeSp = 18;
    int m_subtitleBottomOffset = 20;
    bool m_subtitleStyleInitialized = false;
    QTimer m_autoHideTimer;
    QTimer m_gestureFeedbackTimer;
    QTimer m_accumulatedSeekResetTimer;
    bool m_gestureFeedbackVisible = false;
    QString m_gestureFeedbackMessage;
    QString m_gestureFeedbackSecondaryMessage;
    QString m_gestureFeedbackIcon = QStringLiteral("speed");
    bool m_gestureFeedbackDanger = false;
    QString m_contextPlayerErrorMessage;
    QString m_nativePlayerErrorMessage;
    int m_accumulatedSeekDirection = 0;
    qint64 m_accumulatedSeekBaselineMs = 0;
    qint64 m_accumulatedSeekAmountMs = 0;
};
