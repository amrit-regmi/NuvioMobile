#include "mpv_render_widget.h"
#include "native_player_bridge.h"
#include "player_overlay_controller.h"
#include "player_surface_widget.h"

#include <QApplication>
#include <QByteArray>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QKeySequence>
#include <QMainWindow>
#include <QMessageBox>
#include <QObject>
#include <QPointer>
#include <QQuickWindow>
#include <QSGRendererInterface>
#include <QShortcut>
#include <QStandardPaths>
#include <QStackedWidget>
#include <QString>
#include <QSurfaceFormat>
#include <QUrl>
#include <QWebChannel>
#include <QWebEnginePage>
#include <QWebEngineProfile>
#include <QWebEngineSettings>
#include <QWebEngineUrlRequestJob>
#include <QWebEngineUrlScheme>
#include <QWebEngineUrlSchemeHandler>
#include <QWebEngineView>
#include <QWidget>

#ifndef NUVIO_WEB_ROOT
#define NUVIO_WEB_ROOT ""
#endif

namespace {

constexpr auto kScheme = "nuvio";
constexpr auto kHost = "app";

void appendChromiumFlag(const QByteArray &flag)
{
    auto flags = qgetenv("QTWEBENGINE_CHROMIUM_FLAGS");
    if (flags.split(' ').contains(flag)) return;

    if (!flags.isEmpty()) flags += ' ';
    flags += flag;
    qputenv("QTWEBENGINE_CHROMIUM_FLAGS", flags);
}

void configureProcessEnvironment()
{
    appendChromiumFlag("--disable-web-security");
    qputenv("QSG_RHI_BACKEND", "opengl");
}

void configureOpenGLComposition()
{
    QCoreApplication::setAttribute(Qt::AA_ShareOpenGLContexts);
    QQuickWindow::setGraphicsApi(QSGRendererInterface::OpenGL);

    QSurfaceFormat format;
    format.setRenderableType(QSurfaceFormat::OpenGL);
    format.setDepthBufferSize(0);
    format.setStencilBufferSize(0);
    format.setSwapBehavior(QSurfaceFormat::DoubleBuffer);

#if defined(Q_OS_MACOS)
    format.setVersion(3, 2);
    format.setProfile(QSurfaceFormat::CoreProfile);
#endif

    QSurfaceFormat::setDefaultFormat(format);
}

void registerNuvioScheme()
{
    QWebEngineUrlScheme scheme{QByteArray(kScheme)};
    scheme.setSyntax(QWebEngineUrlScheme::Syntax::Host);
    scheme.setFlags(
        QWebEngineUrlScheme::SecureScheme |
        QWebEngineUrlScheme::LocalScheme |
        QWebEngineUrlScheme::LocalAccessAllowed |
        QWebEngineUrlScheme::ContentSecurityPolicyIgnored |
        QWebEngineUrlScheme::CorsEnabled |
        QWebEngineUrlScheme::FetchApiAllowed
    );
    QWebEngineUrlScheme::registerScheme(scheme);
}

QByteArray mimeTypeForPath(const QString &path)
{
    const auto suffix = QFileInfo(path).suffix().toLower();
    if (suffix == "html") return "text/html; charset=utf-8";
    if (suffix == "css") return "text/css; charset=utf-8";
    if (suffix == "js" || suffix == "mjs") return "application/javascript; charset=utf-8";
    if (suffix == "wasm") return "application/wasm";
    if (suffix == "json" || suffix == "map") return "application/json; charset=utf-8";
    if (suffix == "svg") return "image/svg+xml";
    if (suffix == "png") return "image/png";
    if (suffix == "jpg" || suffix == "jpeg") return "image/jpeg";
    if (suffix == "webp") return "image/webp";
    if (suffix == "ico") return "image/x-icon";
    if (suffix == "ttf") return "font/ttf";
    if (suffix == "woff") return "font/woff";
    if (suffix == "woff2") return "font/woff2";
    return "application/octet-stream";
}

QString existingWebRootCandidate(const QString &candidate)
{
    if (candidate.isEmpty()) return {};
    const QDir dir(candidate);
    return QFileInfo(dir.filePath("index.html")).isFile() ? dir.absolutePath() : QString();
}

QString resolveWebRoot()
{
    const auto envRoot = QString::fromLocal8Bit(qgetenv("NUVIO_WEB_ROOT"));
    if (const auto root = existingWebRootCandidate(envRoot); !root.isEmpty()) return root;

    if (const auto root = existingWebRootCandidate(QStringLiteral(NUVIO_WEB_ROOT)); !root.isEmpty()) return root;

    const QDir appDir(QCoreApplication::applicationDirPath());
    if (const auto root = existingWebRootCandidate(appDir.filePath("web")); !root.isEmpty()) return root;
    if (const auto root = existingWebRootCandidate(appDir.filePath("../Resources/web")); !root.isEmpty()) return root;

    return {};
}

QString requestedFilePath(const QString &webRoot, const QUrl &url)
{
    auto path = QUrl::fromPercentEncoding(url.path().toUtf8());
    if (path.isEmpty() || path == "/") path = "/index.html";

    const auto relativePath = QDir::cleanPath(path.mid(1));
    if (relativePath.startsWith("../") || relativePath == ".." || relativePath.contains("/../")) {
        return {};
    }

    const QDir rootDir(webRoot);
    auto filePath = rootDir.filePath(relativePath);
    if (!QFileInfo(filePath).isFile() && QFileInfo(filePath).suffix().isEmpty()) {
        filePath = rootDir.filePath("index.html");
    }
    return filePath;
}

class LocalWebSchemeHandler final : public QWebEngineUrlSchemeHandler
{
public:
    explicit LocalWebSchemeHandler(QString webRoot, QObject *parent = nullptr)
        : QWebEngineUrlSchemeHandler(parent),
          m_webRoot(std::move(webRoot)),
          m_canonicalRoot(QDir(m_webRoot).canonicalPath())
    {
    }

    void requestStarted(QWebEngineUrlRequestJob *job) override
    {
        const auto url = job->requestUrl();
        if (url.host() != kHost) {
            job->fail(QWebEngineUrlRequestJob::UrlNotFound);
            return;
        }

        const auto filePath = requestedFilePath(m_webRoot, url);
        const QFileInfo info(filePath);
        const auto canonicalFile = info.canonicalFilePath();
        if (
            canonicalFile.isEmpty() ||
            !info.isFile() ||
            !canonicalFile.startsWith(m_canonicalRoot + QDir::separator())
        ) {
            job->fail(QWebEngineUrlRequestJob::UrlNotFound);
            return;
        }

        auto *file = new QFile(canonicalFile, job);
        if (!file->open(QIODevice::ReadOnly)) {
            delete file;
            job->fail(QWebEngineUrlRequestJob::RequestFailed);
            return;
        }

        job->reply(mimeTypeForPath(canonicalFile), file);
    }

private:
    QString m_webRoot;
    QString m_canonicalRoot;
};

void configureProfile(QWebEngineProfile *profile)
{
    const auto dataRoot = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    if (dataRoot.isEmpty()) return;

    QDir().mkpath(dataRoot);
    const QDir dataDir(dataRoot);
    profile->setPersistentStoragePath(dataDir.filePath("qt-web-profile/storage"));
    profile->setCachePath(dataDir.filePath("qt-web-profile/cache"));
    profile->setHttpCacheType(QWebEngineProfile::DiskHttpCache);
    profile->setPersistentCookiesPolicy(QWebEngineProfile::ForcePersistentCookies);
}

} // namespace

int main(int argc, char *argv[])
{
    configureProcessEnvironment();
    configureOpenGLComposition();
    registerNuvioScheme();

    QApplication app(argc, argv);
    QApplication::setApplicationName("Nuvio");
    QApplication::setOrganizationName("Nuvio");

    const auto webRoot = resolveWebRoot();
    if (webRoot.isEmpty()) {
        QMessageBox::critical(
            nullptr,
            "Nuvio",
            "Could not find the CMP web bundle. Build the Qt target or set NUVIO_WEB_ROOT."
        );
        return 1;
    }

    auto *profile = new QWebEngineProfile(QStringLiteral("NuvioQt"), &app);
    configureProfile(profile);
    profile->installUrlSchemeHandler(QByteArray(kScheme), new LocalWebSchemeHandler(webRoot, profile));

    NativePlayerBridge nativePlayerBridge(&app);
    PlayerOverlayController playerOverlayController(&app);

    QMainWindow window;
    window.setWindowTitle("Nuvio");
    window.resize(1280, 800);

    auto *stack = new QStackedWidget(&window);
    auto *webView = new QWebEngineView(stack);
    webView->setPage(new QWebEnginePage(profile, webView));
    stack->addWidget(webView);
    stack->setCurrentWidget(webView);
    window.setCentralWidget(stack);

    QWebChannel webChannel(webView);
    webChannel.registerObject(QStringLiteral("nuvioNativePlayer"), &nativePlayerBridge);
    webView->page()->setWebChannel(&webChannel);

    QPointer<MpvRenderWidget> playerWindow;
    QPointer<QWidget> playerContainer;
    const auto ensurePlayerContainer = [&]() -> QWidget * {
        if (playerContainer) return playerContainer;

        auto *nativeWindow = new MpvRenderWidget();
        auto *container = new PlayerSurfaceWidget(nativeWindow, &playerOverlayController, stack);
        container->setFocusPolicy(Qt::StrongFocus);
        playerWindow = nativeWindow;
        playerContainer = container;
        stack->addWidget(container);

        QObject::connect(
            nativeWindow,
            &MpvRenderWidget::playbackSnapshotChanged,
            &playerOverlayController,
            &PlayerOverlayController::setPlaybackSnapshot
        );
        QObject::connect(
            nativeWindow,
            &MpvRenderWidget::playbackSnapshotChanged,
            &nativePlayerBridge,
            &NativePlayerBridge::updatePlaybackSnapshot
        );
        QObject::connect(
            nativeWindow,
            &MpvRenderWidget::tracksChanged,
            &playerOverlayController,
            &PlayerOverlayController::setTracks
        );
        QObject::connect(
            nativeWindow,
            &MpvRenderWidget::tracksChanged,
            &nativePlayerBridge,
            &NativePlayerBridge::updateTracks
        );
        QObject::connect(nativeWindow, &MpvRenderWidget::playbackStopped, &window, [stack, webView] {
            stack->setCurrentWidget(webView);
        });
        QObject::connect(nativeWindow, &MpvRenderWidget::playerError, &playerOverlayController, &PlayerOverlayController::setPlayerError);
        QObject::connect(nativeWindow, &MpvRenderWidget::playerError, &window, [](const QString &message) {
            qWarning().noquote() << "[NuvioPlayer]" << message;
        });

        return container;
    };

    QObject::connect(
        &nativePlayerBridge,
        &NativePlayerBridge::playerContextUpdated,
        &playerOverlayController,
        &PlayerOverlayController::setPlayerContextJson
    );
    QObject::connect(
        &playerOverlayController,
        &PlayerOverlayController::playerActionRequested,
        &nativePlayerBridge,
        &NativePlayerBridge::queuePlayerAction
    );

    QObject::connect(&playerOverlayController, &PlayerOverlayController::pauseRequested, &window, [&playerWindow] {
        if (playerWindow) playerWindow->pause();
    });
    QObject::connect(&playerOverlayController, &PlayerOverlayController::resumeRequested, &window, [&playerWindow] {
        if (playerWindow) playerWindow->resume();
    });
    QObject::connect(&playerOverlayController, &PlayerOverlayController::seekToRequested, &window, [&playerWindow](qint64 positionMs) {
        if (playerWindow) playerWindow->seekTo(positionMs);
    });
    QObject::connect(&playerOverlayController, &PlayerOverlayController::seekByRequested, &window, [&playerWindow](qint64 offsetMs) {
        if (playerWindow) playerWindow->seekBy(offsetMs);
    });
    QObject::connect(&playerOverlayController, &PlayerOverlayController::playbackSpeedRequested, &window, [&playerWindow](double speed) {
        if (playerWindow) playerWindow->setPlaybackSpeed(speed);
    });
    QObject::connect(&playerOverlayController, &PlayerOverlayController::volumeRequested, &window, [&playerWindow](double fraction) {
        if (playerWindow) playerWindow->setVolumeFraction(fraction);
    });
    QObject::connect(&playerOverlayController, &PlayerOverlayController::brightnessRequested, &window, [&playerWindow](double fraction) {
        if (playerWindow) playerWindow->setBrightnessFraction(fraction);
    });
    QObject::connect(&playerOverlayController, &PlayerOverlayController::resizeModeRequested, &window, [&playerWindow](int mode) {
        if (playerWindow) playerWindow->setResizeMode(mode);
    });
    QObject::connect(&playerOverlayController, &PlayerOverlayController::trackRefreshRequested, &window, [&playerWindow] {
        if (playerWindow) playerWindow->refreshTracks();
    });
    QObject::connect(&playerOverlayController, &PlayerOverlayController::audioTrackRequested, &window, [&playerWindow](int index) {
        if (playerWindow) playerWindow->selectAudioTrack(index);
    });
    QObject::connect(&playerOverlayController, &PlayerOverlayController::subtitleTrackRequested, &window, [&playerWindow](int index) {
        if (playerWindow) playerWindow->selectSubtitleTrack(index);
    });
    QObject::connect(&playerOverlayController, &PlayerOverlayController::externalSubtitleRequested, &window, [&playerWindow](const QString &url) {
        if (playerWindow) playerWindow->addExternalSubtitle(url);
    });
    QObject::connect(
        &playerOverlayController,
        &PlayerOverlayController::subtitleStyleRequested,
        &window,
        [&playerWindow](const QString &textColor, bool outlineEnabled, int fontSizeSp, int bottomOffset) {
            if (playerWindow) playerWindow->setSubtitleStyle(textColor, outlineEnabled, fontSizeSp, bottomOffset);
        }
    );
    QObject::connect(&playerOverlayController, &PlayerOverlayController::stopRequested, &window, [stack, webView, &playerWindow] {
        if (playerWindow) playerWindow->stop();
        stack->setCurrentWidget(webView);
    });

    QObject::connect(
        &nativePlayerBridge,
        &NativePlayerBridge::playRequested,
        &window,
        [stack, ensurePlayerContainer, &playerWindow, &playerOverlayController](const QString &url, const QString &headersJson, qint64 startPositionMs) {
            auto *container = ensurePlayerContainer();
            playerOverlayController.resetForPlayback();
            stack->setCurrentWidget(container);
            container->setFocus();
            if (playerWindow) playerWindow->playUrl(url, headersJson, startPositionMs);
        }
    );
    QObject::connect(&nativePlayerBridge, &NativePlayerBridge::pauseRequested, &window, [&playerWindow] {
        if (playerWindow) playerWindow->pause();
    });
    QObject::connect(&nativePlayerBridge, &NativePlayerBridge::resumeRequested, &window, [&playerWindow] {
        if (playerWindow) playerWindow->resume();
    });
    QObject::connect(&nativePlayerBridge, &NativePlayerBridge::seekToRequested, &window, [&playerWindow](qint64 positionMs) {
        if (playerWindow) playerWindow->seekTo(positionMs);
    });
    QObject::connect(&nativePlayerBridge, &NativePlayerBridge::seekByRequested, &window, [&playerWindow](qint64 offsetMs) {
        if (playerWindow) playerWindow->seekBy(offsetMs);
    });
    QObject::connect(&nativePlayerBridge, &NativePlayerBridge::playbackSpeedRequested, &window, [&playerWindow](double speed) {
        if (playerWindow) playerWindow->setPlaybackSpeed(speed);
    });
    QObject::connect(&nativePlayerBridge, &NativePlayerBridge::resizeModeRequested, &window, [&playerWindow](int mode) {
        if (playerWindow) playerWindow->setResizeMode(mode);
    });
    QObject::connect(&nativePlayerBridge, &NativePlayerBridge::audioTrackRequested, &window, [&playerWindow](int index) {
        if (playerWindow) playerWindow->selectAudioTrack(index);
    });
    QObject::connect(&nativePlayerBridge, &NativePlayerBridge::subtitleTrackRequested, &window, [&playerWindow](int index) {
        if (playerWindow) playerWindow->selectSubtitleTrack(index);
    });
    QObject::connect(&nativePlayerBridge, &NativePlayerBridge::externalSubtitleRequested, &window, [&playerWindow](const QString &url) {
        if (playerWindow) playerWindow->addExternalSubtitle(url);
    });
    QObject::connect(&nativePlayerBridge, &NativePlayerBridge::clearExternalSubtitleRequested, &window, [&playerWindow] {
        if (playerWindow) playerWindow->clearExternalSubtitle();
    });
    QObject::connect(&nativePlayerBridge, &NativePlayerBridge::clearExternalSubtitleAndSelectRequested, &window, [&playerWindow](int index) {
        if (!playerWindow) return;
        playerWindow->clearExternalSubtitle();
        playerWindow->selectSubtitleTrack(index);
    });
    QObject::connect(
        &nativePlayerBridge,
        &NativePlayerBridge::subtitleStyleRequested,
        &window,
        [&playerWindow](const QString &textColor, bool outlineEnabled, int fontSizeSp, int bottomOffset) {
            if (playerWindow) playerWindow->setSubtitleStyle(textColor, outlineEnabled, fontSizeSp, bottomOffset);
        }
    );
    QObject::connect(
        &nativePlayerBridge,
        &NativePlayerBridge::stopRequested,
        &window,
        [stack, webView, &playerWindow] {
            if (playerWindow) playerWindow->stop();
            stack->setCurrentWidget(webView);
        }
    );

    auto *escapeShortcut = new QShortcut(QKeySequence(Qt::Key_Escape), &window);
    QObject::connect(escapeShortcut, &QShortcut::activated, &window, [stack, webView, &playerWindow] {
        if (playerWindow) playerWindow->stop();
        stack->setCurrentWidget(webView);
    });

    webView->settings()->setAttribute(QWebEngineSettings::LocalStorageEnabled, true);
    webView->settings()->setAttribute(QWebEngineSettings::LocalContentCanAccessRemoteUrls, true);
    webView->load(QUrl(QStringLiteral("nuvio://app/index.html")));
    ensurePlayerContainer();
    stack->setCurrentWidget(webView);
    window.show();

    return QApplication::exec();
}
