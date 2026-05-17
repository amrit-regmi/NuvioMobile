#include <QApplication>
#include <QByteArray>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QMessageBox>
#include <QObject>
#include <QStandardPaths>
#include <QString>
#include <QUrl>
#include <QWebEngineProfile>
#include <QWebEngineSettings>
#include <QWebEngineUrlRequestJob>
#include <QWebEngineUrlScheme>
#include <QWebEngineUrlSchemeHandler>
#include <QWebEngineView>

#ifndef NUVIO_WEB_ROOT
#define NUVIO_WEB_ROOT ""
#endif

namespace {

constexpr auto kScheme = "nuvio";
constexpr auto kHost = "app";

void registerNuvioScheme()
{
    QWebEngineUrlScheme scheme{QByteArray(kScheme)};
    scheme.setSyntax(QWebEngineUrlScheme::Syntax::Host);
    scheme.setFlags(
        QWebEngineUrlScheme::SecureScheme |
        QWebEngineUrlScheme::LocalScheme |
        QWebEngineUrlScheme::LocalAccessAllowed |
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
    profile->setPersistentStoragePath(QDir(dataRoot).filePath("webengine-storage"));
    profile->setCachePath(QDir(dataRoot).filePath("webengine-cache"));
    profile->setHttpCacheType(QWebEngineProfile::DiskHttpCache);
    profile->setPersistentCookiesPolicy(QWebEngineProfile::ForcePersistentCookies);
}

} // namespace

int main(int argc, char *argv[])
{
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

    auto *profile = QWebEngineProfile::defaultProfile();
    configureProfile(profile);
    profile->installUrlSchemeHandler(QByteArray(kScheme), new LocalWebSchemeHandler(webRoot, profile));

    QWebEngineView view;
    view.setWindowTitle("Nuvio");
    view.resize(1280, 800);
    view.settings()->setAttribute(QWebEngineSettings::LocalStorageEnabled, true);
    view.settings()->setAttribute(QWebEngineSettings::LocalContentCanAccessRemoteUrls, true);
    view.load(QUrl(QStringLiteral("nuvio://app/index.html")));
    view.show();

    return QApplication::exec();
}
