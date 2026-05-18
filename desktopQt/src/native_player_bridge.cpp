#include "native_player_bridge.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QtMath>

NativePlayerBridge::NativePlayerBridge(QObject *parent)
    : QObject(parent)
{
}

void NativePlayerBridge::playUrl(const QString &url, const QString &headersJson, double startPositionMs)
{
    emit playRequested(url, headersJson, qMax<qint64>(0, qRound64(startPositionMs)));
}

void NativePlayerBridge::pause()
{
    emit pauseRequested();
}

void NativePlayerBridge::resume()
{
    emit resumeRequested();
}

void NativePlayerBridge::stop()
{
    emit stopRequested();
}

void NativePlayerBridge::seekTo(double positionMs)
{
    emit seekToRequested(qMax<qint64>(0, qRound64(positionMs)));
}

void NativePlayerBridge::seekBy(double offsetMs)
{
    emit seekByRequested(qRound64(offsetMs));
}

void NativePlayerBridge::setPlaybackSpeed(double speed)
{
    emit playbackSpeedRequested(qBound(0.25, speed, 4.0));
}

void NativePlayerBridge::setResizeMode(int mode)
{
    emit resizeModeRequested(qBound(0, mode, 2));
}

void NativePlayerBridge::selectAudioTrack(int index)
{
    emit audioTrackRequested(index);
}

void NativePlayerBridge::selectSubtitleTrack(int index)
{
    emit subtitleTrackRequested(index);
}

void NativePlayerBridge::setSubtitleUri(const QString &url)
{
    if (url.trimmed().isEmpty()) return;
    emit externalSubtitleRequested(url.trimmed());
}

void NativePlayerBridge::clearExternalSubtitle()
{
    emit clearExternalSubtitleRequested();
}

void NativePlayerBridge::clearExternalSubtitleAndSelect(int index)
{
    emit clearExternalSubtitleAndSelectRequested(index);
}

void NativePlayerBridge::applySubtitleStyle(const QString &styleJson)
{
    const auto document = QJsonDocument::fromJson(styleJson.toUtf8());
    const auto style = document.object();
    const auto textColor = style.value(QStringLiteral("textColor")).toString(QStringLiteral("#FFFFFFFF"));
    const auto outlineEnabled = style.value(QStringLiteral("outlineEnabled")).toBool(true);
    const auto fontSizeSp = style.value(QStringLiteral("fontSizeSp")).toInt(18);
    const auto bottomOffset = style.value(QStringLiteral("bottomOffset")).toInt(12);
    emit subtitleStyleRequested(textColor, outlineEnabled, fontSizeSp, bottomOffset);
}

void NativePlayerBridge::updatePlayerContext(const QString &contextJson)
{
    emit playerContextUpdated(contextJson);
}

QString NativePlayerBridge::takePlayerAction()
{
    if (m_pendingPlayerActions.isEmpty()) return {};
    const auto actionToken = m_pendingPlayerActions.takeFirst();
    emit pendingPlayerActionChanged();
    const auto separator = actionToken.indexOf(QLatin1Char('\n'));
    return separator >= 0 ? actionToken.mid(separator + 1) : actionToken;
}

QString NativePlayerBridge::takePlayerSnapshot() const
{
    return m_playerSnapshotJson;
}

QString NativePlayerBridge::playerSnapshotJson() const
{
    return m_playerSnapshotJson;
}

QString NativePlayerBridge::playerTracksJson() const
{
    return m_playerTracksJson;
}

QString NativePlayerBridge::pendingPlayerAction() const
{
    return m_pendingPlayerActions.value(0);
}

void NativePlayerBridge::acknowledgePlayerAction(const QString &actionToken)
{
    if (m_pendingPlayerActions.isEmpty()) return;
    if (actionToken.isEmpty() || m_pendingPlayerActions.first() == actionToken) {
        m_pendingPlayerActions.takeFirst();
        emit pendingPlayerActionChanged();
        return;
    }

    if (m_pendingPlayerActions.removeOne(actionToken)) {
        emit pendingPlayerActionChanged();
    }
}

void NativePlayerBridge::queuePlayerAction(const QString &action)
{
    if (action.trimmed().isEmpty()) return;
    const auto wasEmpty = m_pendingPlayerActions.isEmpty();
    const auto actionToken = QStringLiteral("%1\n%2").arg(m_nextPlayerActionId++).arg(action.trimmed());
    m_pendingPlayerActions.append(actionToken);
    if (wasEmpty) emit pendingPlayerActionChanged();
}

void NativePlayerBridge::updatePlaybackSnapshot(
    qint64 positionMs,
    qint64 durationMs,
    bool playing,
    bool loading,
    double playbackSpeed,
    bool ended
)
{
    QJsonObject snapshot;
    snapshot.insert(QStringLiteral("positionMs"), positionMs);
    snapshot.insert(QStringLiteral("durationMs"), durationMs);
    snapshot.insert(QStringLiteral("playing"), playing);
    snapshot.insert(QStringLiteral("loading"), loading);
    snapshot.insert(QStringLiteral("ended"), ended);
    snapshot.insert(QStringLiteral("playbackSpeed"), playbackSpeed);
    const auto nextSnapshotJson = QString::fromUtf8(QJsonDocument(snapshot).toJson(QJsonDocument::Compact));
    if (nextSnapshotJson == m_playerSnapshotJson) return;
    m_playerSnapshotJson = nextSnapshotJson;
    emit playerSnapshotJsonChanged();
}

namespace {
QJsonArray tracksToJson(const QVariantList &tracks)
{
    QJsonArray rows;
    for (const auto &trackVariant : tracks) {
        const auto track = trackVariant.toMap();
        QJsonObject row;
        row.insert(QStringLiteral("mpvIndex"), track.value(QStringLiteral("index")).toInt());
        row.insert(QStringLiteral("id"), QString::number(track.value(QStringLiteral("index")).toInt()));
        row.insert(QStringLiteral("label"), track.value(QStringLiteral("label")).toString());
        row.insert(QStringLiteral("language"), track.value(QStringLiteral("language")).toString());
        row.insert(QStringLiteral("selected"), track.value(QStringLiteral("selected")).toBool());
        rows.append(row);
    }
    return rows;
}
}

void NativePlayerBridge::updateTracks(
    const QVariantList &audioTracks,
    const QVariantList &subtitleTracks,
    int selectedAudioIndex,
    int selectedSubtitleIndex
)
{
    QJsonObject tracks;
    tracks.insert(QStringLiteral("audio"), tracksToJson(audioTracks));
    tracks.insert(QStringLiteral("subtitles"), tracksToJson(subtitleTracks));
    tracks.insert(QStringLiteral("selectedAudioIndex"), selectedAudioIndex);
    tracks.insert(QStringLiteral("selectedSubtitleIndex"), selectedSubtitleIndex);

    const auto nextTracksJson = QString::fromUtf8(QJsonDocument(tracks).toJson(QJsonDocument::Compact));
    if (nextTracksJson == m_playerTracksJson) return;
    m_playerTracksJson = nextTracksJson;
    emit playerTracksJsonChanged();
}
