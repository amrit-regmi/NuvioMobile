#pragma once

#include <QByteArray>
#include <QOpenGLFunctions>
#include <QOpenGLWindow>
#include <QString>

#include <mpv/client.h>
#include <mpv/render.h>
#include <mpv/render_gl.h>

class MpvRenderWidget final : public QOpenGLWindow, protected QOpenGLFunctions
{
    Q_OBJECT

public:
    explicit MpvRenderWidget(QWindow *parent = nullptr);
    ~MpvRenderWidget() override;

public slots:
    void playUrl(const QString &url, const QString &headersJson = QString(), qint64 startPositionMs = 0);
    void pause();
    void resume();
    void stop();
    void seekTo(qint64 positionMs);
    void seekBy(qint64 offsetMs);

signals:
    void playbackStarted();
    void playbackStopped();
    void playerError(const QString &message);

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
    void reportMpvError(const QString &context, int errorCode);

    static void *getProcAddress(void *ctx, const char *name);
    static void onRenderUpdate(void *ctx);
    static void onMpvWakeup(void *ctx);

    mpv_handle *m_mpv = nullptr;
    mpv_render_context *m_renderContext = nullptr;
    bool m_glReady = false;
    bool m_stopping = false;
    PendingPlayback m_pendingPlayback;
};
