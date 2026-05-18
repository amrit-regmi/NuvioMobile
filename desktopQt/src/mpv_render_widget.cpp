#include "mpv_render_widget.h"

#include <QByteArray>
#include <QDebug>
#include <QJsonDocument>
#include <QJsonObject>
#include <QOpenGLContext>
#include <QSize>
#include <QStringList>
#include <QSurfaceFormat>
#include <QVariant>
#include <QVariantMap>
#include <QtMath>

#include <cstring>

namespace {

constexpr auto kMpvClientName = "nuvio-qt";

QByteArray secondsArg(qint64 millis)
{
    return QByteArray::number(static_cast<double>(millis) / 1000.0, 'f', 3);
}

qint64 secondsToMillis(double seconds)
{
    if (seconds <= 0.0) return 0;
    return qRound64(seconds * 1000.0);
}

QVariant mpvNodeToVariant(const mpv_node &node)
{
    switch (node.format) {
    case MPV_FORMAT_STRING:
        return QString::fromUtf8(node.u.string ? node.u.string : "");
    case MPV_FORMAT_FLAG:
        return static_cast<bool>(node.u.flag);
    case MPV_FORMAT_INT64:
        return QVariant::fromValue<qlonglong>(node.u.int64);
    case MPV_FORMAT_DOUBLE:
        return node.u.double_;
    case MPV_FORMAT_NODE_ARRAY: {
        QVariantList list;
        if (!node.u.list) return list;
        for (int i = 0; i < node.u.list->num; ++i) {
            list.append(mpvNodeToVariant(node.u.list->values[i]));
        }
        return list;
    }
    case MPV_FORMAT_NODE_MAP: {
        QVariantMap map;
        if (!node.u.list) return map;
        for (int i = 0; i < node.u.list->num; ++i) {
            map.insert(QString::fromUtf8(node.u.list->keys[i]), mpvNodeToVariant(node.u.list->values[i]));
        }
        return map;
    }
    default:
        return {};
    }
}

QString trackLabel(const QVariantMap &track, const QString &fallbackPrefix)
{
    const auto title = track.value(QStringLiteral("title")).toString().trimmed();
    const auto lang = track.value(QStringLiteral("lang")).toString().trimmed();
    const auto id = track.value(QStringLiteral("id")).toInt();
    if (!title.isEmpty() && !lang.isEmpty()) return title + QStringLiteral(" (") + lang + QStringLiteral(")");
    if (!title.isEmpty()) return title;
    if (!lang.isEmpty()) return lang.toUpper();
    return fallbackPrefix + QStringLiteral(" ") + QString::number(id);
}

} // namespace

MpvRenderWidget::MpvRenderWidget(QWidget *parent)
    : QOpenGLWidget(parent)
{
    setFormat(QSurfaceFormat::defaultFormat());
    setUpdateBehavior(QOpenGLWidget::NoPartialUpdate);
    setAutoFillBackground(false);
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
    m_loading = false;
    m_paused = true;
    m_positionMs = 0;
    m_durationMs = 0;
    emitPlaybackSnapshot();
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

void MpvRenderWidget::setPlaybackSpeed(double speed)
{
    if (!m_mpv) return;
    double value = qBound(0.25, speed, 4.0);
    mpv_set_property_async(m_mpv, 0, "speed", MPV_FORMAT_DOUBLE, &value);
}

void MpvRenderWidget::setVolumeFraction(double fraction)
{
    if (!m_mpv) return;
    double value = qBound(0.0, fraction, 1.0) * 100.0;
    mpv_set_property_async(m_mpv, 0, "volume", MPV_FORMAT_DOUBLE, &value);
    int mute = value <= 0.01 ? 1 : 0;
    mpv_set_property_async(m_mpv, 0, "mute", MPV_FORMAT_FLAG, &mute);
}

void MpvRenderWidget::setBrightnessFraction(double fraction)
{
    if (!m_mpv) return;
    qint64 value = qRound64((qBound(0.0, fraction, 1.0) - 0.5) * 200.0);
    mpv_set_property_async(m_mpv, 0, "brightness", MPV_FORMAT_INT64, &value);
}

void MpvRenderWidget::setResizeMode(int mode)
{
    if (!m_mpv) return;
    double panscan = mode == 1 ? 1.0 : 0.0;
    double zoom = mode == 2 ? 0.18 : 0.0;
    mpv_set_property_async(m_mpv, 0, "panscan", MPV_FORMAT_DOUBLE, &panscan);
    mpv_set_property_async(m_mpv, 0, "video-zoom", MPV_FORMAT_DOUBLE, &zoom);
}

void MpvRenderWidget::selectAudioTrack(int index)
{
    if (!m_mpv) return;
    if (index < 0) {
        mpv_set_property_string(m_mpv, "aid", "no");
    } else {
        const auto id = QByteArray::number(index);
        mpv_set_property_string(m_mpv, "aid", id.constData());
    }
    refreshTrackList();
}

void MpvRenderWidget::selectSubtitleTrack(int index)
{
    if (!m_mpv) return;
    if (index < 0) {
        mpv_set_property_string(m_mpv, "sid", "no");
    } else {
        const auto id = QByteArray::number(index);
        mpv_set_property_string(m_mpv, "sid", id.constData());
    }
    refreshTrackList();
}

void MpvRenderWidget::refreshTracks()
{
    refreshTrackList();
}

void MpvRenderWidget::addExternalSubtitle(const QString &url)
{
    if (!m_mpv || url.trimmed().isEmpty()) return;
    const QByteArray urlBytes = url.toUtf8();
    const char *command[] = {"sub-add", urlBytes.constData(), "select", nullptr};
    const int result = mpv_command_async(m_mpv, 0, command);
    if (result < 0) reportMpvError("Failed to add subtitle", result);
    refreshTrackList();
}

void MpvRenderWidget::clearExternalSubtitle()
{
    if (!m_mpv) return;
    mpv_set_property_string(m_mpv, "sid", "no");
    refreshTrackList();
}

void MpvRenderWidget::setSubtitleStyle(const QString &textColor, bool outlineEnabled, int fontSizeSp, int bottomOffset)
{
    if (!m_mpv) return;

    const QByteArray colorBytes = textColor.trimmed().toUpper().toUtf8();
    mpv_set_property_string(m_mpv, "sub-ass-override", "yes");
    mpv_set_property_string(m_mpv, "sub-color", colorBytes.constData());
    mpv_set_property_string(m_mpv, "sub-outline-color", "#000000");

    double outlineSize = outlineEnabled ? 1.65 : 0.0;
    mpv_set_property_async(m_mpv, 0, "sub-outline-size", MPV_FORMAT_DOUBLE, &outlineSize);

    double fontSize = qBound(24.0, static_cast<double>(fontSizeSp) * 3.0, 96.0);
    mpv_set_property_async(m_mpv, 0, "sub-font-size", MPV_FORMAT_DOUBLE, &fontSize);

    qint64 subtitlePosition = qBound(0, 100 - (bottomOffset / 2), 150);
    mpv_set_property_async(m_mpv, 0, "sub-pos", MPV_FORMAT_INT64, &subtitlePosition);
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
    clearOpenGLErrors();
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
            m_loading = false;
            m_ended = false;
            refreshTrackList();
            emitPlaybackSnapshot();
            emit playbackStarted();
            break;
        case MPV_EVENT_START_FILE:
            m_loading = true;
            m_ended = false;
            emitPlaybackSnapshot();
            break;
        case MPV_EVENT_END_FILE:
            handleEndFile(static_cast<mpv_event_end_file *>(event->data));
            break;
        case MPV_EVENT_PROPERTY_CHANGE: {
            auto *property = static_cast<mpv_event_property *>(event->data);
            if (!property || !property->name) break;
            const auto name = property->name;
            if (std::strcmp(name, "time-pos") == 0 && property->format == MPV_FORMAT_DOUBLE && property->data) {
                m_positionMs = secondsToMillis(*static_cast<double *>(property->data));
                emitPlaybackSnapshot();
            } else if (std::strcmp(name, "duration") == 0 && property->format == MPV_FORMAT_DOUBLE && property->data) {
                m_durationMs = secondsToMillis(*static_cast<double *>(property->data));
                emitPlaybackSnapshot();
            } else if (std::strcmp(name, "pause") == 0 && property->format == MPV_FORMAT_FLAG && property->data) {
                m_paused = *static_cast<int *>(property->data) != 0;
                emitPlaybackSnapshot();
            } else if (std::strcmp(name, "speed") == 0 && property->format == MPV_FORMAT_DOUBLE && property->data) {
                m_playbackSpeed = *static_cast<double *>(property->data);
                emitPlaybackSnapshot();
            } else if (
                std::strcmp(name, "track-list") == 0 ||
                std::strcmp(name, "aid") == 0 ||
                std::strcmp(name, "sid") == 0
            ) {
                refreshTrackList();
            }
            break;
        }
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
#if defined(Q_OS_MACOS)
    mpv_set_option_string(m_mpv, "hwdec", "auto-copy-safe");
#else
    mpv_set_option_string(m_mpv, "hwdec", "auto-safe");
#endif
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

    mpv_observe_property(m_mpv, 0, "time-pos", MPV_FORMAT_DOUBLE);
    mpv_observe_property(m_mpv, 0, "duration", MPV_FORMAT_DOUBLE);
    mpv_observe_property(m_mpv, 0, "pause", MPV_FORMAT_FLAG);
    mpv_observe_property(m_mpv, 0, "speed", MPV_FORMAT_DOUBLE);
    mpv_observe_property(m_mpv, 0, "track-list", MPV_FORMAT_NODE);
    mpv_observe_property(m_mpv, 0, "aid", MPV_FORMAT_STRING);
    mpv_observe_property(m_mpv, 0, "sid", MPV_FORMAT_STRING);

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

    clearOpenGLErrors();
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
    m_loading = true;
    m_ended = false;
    m_paused = false;
    emitPlaybackSnapshot();

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
    m_paused = paused;
    emitPlaybackSnapshot();
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

void MpvRenderWidget::handleEndFile(const mpv_event_end_file *endFile)
{
    const auto reason = endFile ? endFile->reason : MPV_END_FILE_REASON_ERROR;
    if (reason == MPV_END_FILE_REASON_REDIRECT) {
        m_loading = true;
        m_ended = false;
        emitPlaybackSnapshot();
        return;
    }

    m_loading = false;
    m_paused = true;

    if (reason == MPV_END_FILE_REASON_ERROR) {
        m_ended = false;
        const int errorCode = endFile ? endFile->error : MPV_ERROR_UNKNOWN_FORMAT;
        reportPlaybackError(QStringLiteral("Playback failed: %1").arg(QString::fromUtf8(mpv_error_string(errorCode))));
        emitPlaybackSnapshot();
        m_stopping = false;
        return;
    }

    m_ended = reason == MPV_END_FILE_REASON_EOF;
    emitPlaybackSnapshot();
    if (!m_stopping && reason == MPV_END_FILE_REASON_STOP) emit playbackStopped();
    m_stopping = false;
}

void MpvRenderWidget::emitPlaybackSnapshot()
{
    emit playbackSnapshotChanged(
        m_positionMs,
        m_durationMs,
        !m_paused && !m_loading && !m_ended,
        m_loading,
        m_playbackSpeed,
        m_ended
    );
}

void MpvRenderWidget::refreshTrackList()
{
    if (!m_mpv) return;

    mpv_node node{};
    if (mpv_get_property(m_mpv, "track-list", MPV_FORMAT_NODE, &node) < 0) return;

    QVariantList audioTracks;
    QVariantList subtitleTracks;
    int selectedAudioIndex = -1;
    int selectedSubtitleIndex = -1;

    const auto tracks = mpvNodeToVariant(node).toList();
    mpv_free_node_contents(&node);

    for (const auto &trackVariant : tracks) {
        const auto track = trackVariant.toMap();
        const auto type = track.value(QStringLiteral("type")).toString();
        bool hasId = false;
        const auto id = track.value(QStringLiteral("id")).toInt(&hasId);
        if (!hasId) continue;
        if (id < 0) continue;

        QVariantMap row;
        row.insert(QStringLiteral("index"), id);
        row.insert(QStringLiteral("language"), track.value(QStringLiteral("lang")).toString());
        row.insert(QStringLiteral("selected"), track.value(QStringLiteral("selected")).toBool());

        if (type == QStringLiteral("audio")) {
            row.insert(QStringLiteral("label"), trackLabel(track, QStringLiteral("Audio")));
            audioTracks.append(row);
            if (row.value(QStringLiteral("selected")).toBool()) selectedAudioIndex = id;
        } else if (type == QStringLiteral("sub")) {
            row.insert(QStringLiteral("label"), trackLabel(track, QStringLiteral("Subtitle")));
            subtitleTracks.append(row);
            if (row.value(QStringLiteral("selected")).toBool()) selectedSubtitleIndex = id;
        }
    }

    emit tracksChanged(audioTracks, subtitleTracks, selectedAudioIndex, selectedSubtitleIndex);
}

void MpvRenderWidget::reportMpvError(const QString &context, int errorCode)
{
    const auto message = context + ": " + QString::fromUtf8(mpv_error_string(errorCode));
    reportPlaybackError(message);
}

void MpvRenderWidget::reportPlaybackError(const QString &message)
{
    m_loading = false;
    m_paused = true;
    qWarning().noquote() << message;
    emitPlaybackSnapshot();
    emit playerError(message);
}

void MpvRenderWidget::clearOpenGLErrors()
{
    while (glGetError() != GL_NO_ERROR) {
    }
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
