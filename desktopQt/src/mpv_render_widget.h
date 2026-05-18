#pragma once

#include <QByteArray>
#include <QOpenGLFunctions>
#include <QOpenGLWidget>
#include <QString>
#include <QVariantList>

#include <mpv/client.h>
#include <mpv/render.h>
#include <mpv/render_gl.h>

class MpvRenderWidget final : public QOpenGLWidget, protected QOpenGLFunctions
{
    Q_OBJECT

public:
    explicit MpvRenderWidget(QWidget *parent = nullptr);
    ~MpvRenderWidget() override;

public slots:
    void playUrl(const QString &url, const QString &headersJson = QString(), qint64 startPositionMs = 0);
    void pause();
    void resume();
    void stop();
    void seekTo(qint64 positionMs);
    void seekBy(qint64 offsetMs);
    void setPlaybackSpeed(double speed);
    void setVolumeFraction(double fraction);
    void setBrightnessFraction(double fraction);
    void setResizeMode(int mode);
    void selectAudioTrack(int index);
    void selectSubtitleTrack(int index);
    void refreshTracks();
    void addExternalSubtitle(const QString &url);
    void clearExternalSubtitle();
    void setSubtitleStyle(const QString &textColor, bool outlineEnabled, int fontSizeSp, int bottomOffset);

signals:
    void playbackStarted();
    void playbackStopped();
    void playerError(const QString &message);
    void playbackSnapshotChanged(qint64 positionMs, qint64 durationMs, bool playing, bool loading, double playbackSpeed, bool ended);
    void tracksChanged(const QVariantList &audioTracks, const QVariantList &subtitleTracks, int selectedAudioIndex, int selectedSubtitleIndex);

protected:
    void initializeGL() override;
    void paintGL() override;

private slots:
    void processMpvEvents();

private:
    struct PendingPlayback {
        bool valid = false;
        QString url;
        QString headersJson;
        qint64 startPositionMs = 0;
    };

    bool ensureMpv();
    bool ensureRenderContext();
    void destroyMpv();
    void playPendingIfReady();
    void playUrlNow(const QString &url, const QString &headersJson, qint64 startPositionMs);
    void applyHttpHeaders(const QString &headersJson);
    void setPause(bool paused);
    void commandSeek(qint64 positionMs, const char *mode);
    void handleEndFile(const mpv_event_end_file *endFile);
    void emitPlaybackSnapshot();
    void refreshTrackList();
    void reportMpvError(const QString &context, int errorCode);
    void reportPlaybackError(const QString &message);
    void clearOpenGLErrors();

    static void *getProcAddress(void *ctx, const char *name);
    static void onRenderUpdate(void *ctx);
    static void onMpvWakeup(void *ctx);

    mpv_handle *m_mpv = nullptr;
    mpv_render_context *m_renderContext = nullptr;
    bool m_glReady = false;
    bool m_stopping = false;
    bool m_loading = false;
    bool m_paused = false;
    bool m_ended = false;
    qint64 m_positionMs = 0;
    qint64 m_durationMs = 0;
    double m_playbackSpeed = 1.0;
    PendingPlayback m_pendingPlayback;
};
