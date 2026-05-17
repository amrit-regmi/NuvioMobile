#include "mpv_render_widget.h"

#include <QByteArray>
#include <QDebug>
#include <QJsonDocument>
#include <QJsonObject>
#include <QOpenGLContext>
#include <QSize>
#include <QStringList>
#include <QVariant>

namespace {

constexpr auto kMpvClientName = "nuvio-qt";

QByteArray secondsArg(qint64 millis)
{
    return QByteArray::number(static_cast<double>(millis) / 1000.0, 'f', 3);
}

} // namespace

MpvRenderWidget::MpvRenderWidget(QWindow *parent)
    : QOpenGLWindow(QOpenGLWindow::NoPartialUpdate, parent)
{
}

MpvRenderWidget::~MpvRenderWidget()
{
    destroyMpv();
}

void MpvRenderWidget::playUrl(const QString &url, const QString &headersJson, qint64 startPositionMs)
{
    if (url.trimmed().isEmpty()) return;

    if (!m_glReady || !m_renderContext) {
        m_pendingPlayback = PendingPlayback{
            true,
            url,
            headersJson,
            startPositionMs,
        };
        update();
        return;
    }

    playUrlNow(url, headersJson, startPositionMs);
}

void MpvRenderWidget::pause()
{
    setPause(true);
}

void MpvRenderWidget::resume()
{
    setPause(false);
}

void MpvRenderWidget::stop()
{
    if (!m_mpv) return;
    m_stopping = true;
    const char *command[] = {"stop", nullptr};
    mpv_command_async(m_mpv, 0, command);
    emit playbackStopped();
}

void MpvRenderWidget::seekTo(qint64 positionMs)
{
    commandSeek(positionMs, "absolute");
}

void MpvRenderWidget::seekBy(qint64 offsetMs)
{
    commandSeek(offsetMs, "relative");
}

void MpvRenderWidget::initializeGL()
{
    initializeOpenGLFunctions();
    glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
    m_glReady = true;

    if (ensureMpv() && ensureRenderContext()) {
        playPendingIfReady();
    }
}

void MpvRenderWidget::paintGL()
{
    glClear(GL_COLOR_BUFFER_BIT);
    if (!m_renderContext) return;

    const auto pixelRatio = devicePixelRatioF();
    const auto targetSize = QSize(
        qMax(1, qRound(width() * pixelRatio)),
        qMax(1, qRound(height() * pixelRatio))
    );

    mpv_opengl_fbo fbo{
        static_cast<int>(defaultFramebufferObject()),
        targetSize.width(),
        targetSize.height(),
        0,
    };
    int flipY = 1;
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_OPENGL_FBO, &fbo},
        {MPV_RENDER_PARAM_FLIP_Y, &flipY},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    mpv_render_context_render(m_renderContext, params);
}

void MpvRenderWidget::processMpvEvents()
{
    if (!m_mpv) return;

    while (true) {
        mpv_event *event = mpv_wait_event(m_mpv, 0);
        if (!event || event->event_id == MPV_EVENT_NONE) break;

        switch (event->event_id) {
        case MPV_EVENT_LOG_MESSAGE: {
            auto *message = static_cast<mpv_event_log_message *>(event->data);
            if (message) {
                qDebug().noquote()
                    << "[mpv]"
                    << message->prefix
                    << message->level
                    << QString::fromUtf8(message->text).trimmed();
            }
            break;
        }
        case MPV_EVENT_FILE_LOADED:
            m_stopping = false;
            emit playbackStarted();
            break;
        case MPV_EVENT_END_FILE:
            if (!m_stopping) emit playbackStopped();
            m_stopping = false;
            break;
        case MPV_EVENT_SHUTDOWN:
            emit playbackStopped();
            break;
        default:
            break;
        }
    }
}

bool MpvRenderWidget::ensureMpv()
{
    if (m_mpv) return true;

    m_mpv = mpv_create_client(nullptr, kMpvClientName);
    if (!m_mpv) {
        emit playerError("Failed to create libmpv client.");
        return false;
    }

    mpv_set_option_string(m_mpv, "terminal", "no");
    mpv_set_option_string(m_mpv, "msg-level", "all=warn");
    mpv_set_option_string(m_mpv, "vo", "libmpv");
    mpv_set_option_string(m_mpv, "hwdec", "auto-safe");
    mpv_set_option_string(m_mpv, "gpu-api", "opengl");
    mpv_set_option_string(m_mpv, "idle", "yes");
    mpv_set_option_string(m_mpv, "keep-open", "no");
    mpv_set_option_string(m_mpv, "force-window", "no");
    mpv_set_option_string(m_mpv, "osd-level", "0");
    mpv_request_log_messages(m_mpv, "warn");
    mpv_set_wakeup_callback(m_mpv, &MpvRenderWidget::onMpvWakeup, this);

    const int result = mpv_initialize(m_mpv);
    if (result < 0) {
        reportMpvError("Failed to initialize libmpv", result);
        destroyMpv();
        return false;
    }

    return true;
}

bool MpvRenderWidget::ensureRenderContext()
{
    if (m_renderContext) return true;
    if (!m_mpv || !m_glReady) return false;

    mpv_opengl_init_params glInitParams{
        &MpvRenderWidget::getProcAddress,
        nullptr,
    };
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_OPENGL)},
        {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &glInitParams},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };

    const int result = mpv_render_context_create(&m_renderContext, m_mpv, params);
    if (result < 0) {
        reportMpvError("Failed to create libmpv OpenGL render context", result);
        return false;
    }

    mpv_render_context_set_update_callback(m_renderContext, &MpvRenderWidget::onRenderUpdate, this);
    return true;
}

void MpvRenderWidget::destroyMpv()
{
    makeCurrent();
    if (m_renderContext) {
        mpv_render_context_set_update_callback(m_renderContext, nullptr, nullptr);
        mpv_render_context_free(m_renderContext);
        m_renderContext = nullptr;
    }
    doneCurrent();

    if (m_mpv) {
        mpv_set_wakeup_callback(m_mpv, nullptr, nullptr);
        mpv_terminate_destroy(m_mpv);
        m_mpv = nullptr;
    }
}

void MpvRenderWidget::playPendingIfReady()
{
    if (!m_pendingPlayback.valid || !m_renderContext) return;

    const auto pending = m_pendingPlayback;
    m_pendingPlayback = PendingPlayback{};
    playUrlNow(pending.url, pending.headersJson, pending.startPositionMs);
}

void MpvRenderWidget::playUrlNow(const QString &url, const QString &headersJson, qint64 startPositionMs)
{
    if (!ensureMpv()) return;

    applyHttpHeaders(headersJson);

    const QByteArray urlBytes = url.toUtf8();
    if (startPositionMs > 0) {
        const QByteArray options = "start=" + secondsArg(startPositionMs);
        const char *command[] = {"loadfile", urlBytes.constData(), "replace", options.constData(), nullptr};
        const int result = mpv_command_async(m_mpv, 0, command);
        if (result < 0) reportMpvError("Failed to load media", result);
    } else {
        const char *command[] = {"loadfile", urlBytes.constData(), "replace", nullptr};
        const int result = mpv_command_async(m_mpv, 0, command);
        if (result < 0) reportMpvError("Failed to load media", result);
    }
}

void MpvRenderWidget::applyHttpHeaders(const QString &headersJson)
{
    QStringList fields;
    const auto document = QJsonDocument::fromJson(headersJson.toUtf8());
    if (document.isObject()) {
        const auto object = document.object();
        for (auto it = object.begin(); it != object.end(); ++it) {
            const auto key = it.key().trimmed();
            const auto value = it.value().toVariant().toString().trimmed();
            if (!key.isEmpty() && !value.isEmpty()) {
                fields += key + ": " + value;
            }
        }
    }

    const auto headerFields = fields.join(",");
    const QByteArray headerBytes = headerFields.toUtf8();
    mpv_set_option_string(m_mpv, "http-header-fields", headerBytes.constData());
}

void MpvRenderWidget::setPause(bool paused)
{
    if (!m_mpv) return;
    int value = paused ? 1 : 0;
    mpv_set_property_async(m_mpv, 0, "pause", MPV_FORMAT_FLAG, &value);
}

void MpvRenderWidget::commandSeek(qint64 positionMs, const char *mode)
{
    if (!m_mpv) return;
    const QByteArray value = secondsArg(positionMs);
    const char *command[] = {"seek", value.constData(), mode, nullptr};
    const int result = mpv_command_async(m_mpv, 0, command);
    if (result < 0) reportMpvError("Failed to seek", result);
}

void MpvRenderWidget::reportMpvError(const QString &context, int errorCode)
{
    const auto message = context + ": " + QString::fromUtf8(mpv_error_string(errorCode));
    qWarning().noquote() << message;
    emit playerError(message);
}

void *MpvRenderWidget::getProcAddress(void *, const char *name)
{
    auto *context = QOpenGLContext::currentContext();
    if (!context) return nullptr;
    return reinterpret_cast<void *>(context->getProcAddress(QByteArray(name)));
}

void MpvRenderWidget::onRenderUpdate(void *ctx)
{
    auto *widget = static_cast<MpvRenderWidget *>(ctx);
    QMetaObject::invokeMethod(widget, "update", Qt::QueuedConnection);
}

void MpvRenderWidget::onMpvWakeup(void *ctx)
{
    auto *widget = static_cast<MpvRenderWidget *>(ctx);
    QMetaObject::invokeMethod(widget, "processMpvEvents", Qt::QueuedConnection);
}
