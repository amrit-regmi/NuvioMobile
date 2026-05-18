#pragma once

#include <QObject>
#include <QString>
#include <QStringList>
#include <QVariantList>

class NativePlayerBridge final : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QString playerSnapshotJson READ playerSnapshotJson NOTIFY playerSnapshotJsonChanged)
    Q_PROPERTY(QString playerTracksJson READ playerTracksJson NOTIFY playerTracksJsonChanged)
    Q_PROPERTY(QString pendingPlayerAction READ pendingPlayerAction NOTIFY pendingPlayerActionChanged)

public:
    explicit NativePlayerBridge(QObject *parent = nullptr);

    Q_INVOKABLE void playUrl(const QString &url, const QString &headersJson, double startPositionMs);
    Q_INVOKABLE void pause();
    Q_INVOKABLE void resume();
    Q_INVOKABLE void stop();
    Q_INVOKABLE void seekTo(double positionMs);
    Q_INVOKABLE void seekBy(double offsetMs);
    Q_INVOKABLE void setPlaybackSpeed(double speed);
    Q_INVOKABLE void setResizeMode(int mode);
    Q_INVOKABLE void selectAudioTrack(int index);
    Q_INVOKABLE void selectSubtitleTrack(int index);
    Q_INVOKABLE void setSubtitleUri(const QString &url);
    Q_INVOKABLE void clearExternalSubtitle();
    Q_INVOKABLE void clearExternalSubtitleAndSelect(int index);
    Q_INVOKABLE void applySubtitleStyle(const QString &styleJson);
    Q_INVOKABLE void updatePlayerContext(const QString &contextJson);
    Q_INVOKABLE QString takePlayerAction();
    Q_INVOKABLE QString takePlayerSnapshot() const;
    Q_INVOKABLE void acknowledgePlayerAction(const QString &actionToken);
    QString playerSnapshotJson() const;
    QString playerTracksJson() const;
    QString pendingPlayerAction() const;

public slots:
    void queuePlayerAction(const QString &action);
    void updatePlaybackSnapshot(qint64 positionMs, qint64 durationMs, bool playing, bool loading, double playbackSpeed, bool ended);
    void updateTracks(const QVariantList &audioTracks, const QVariantList &subtitleTracks, int selectedAudioIndex, int selectedSubtitleIndex);

signals:
    void playRequested(const QString &url, const QString &headersJson, qint64 startPositionMs);
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
    void clearExternalSubtitleRequested();
    void clearExternalSubtitleAndSelectRequested(int index);
    void subtitleStyleRequested(const QString &textColor, bool outlineEnabled, int fontSizeSp, int bottomOffset);
    void playerContextUpdated(const QString &contextJson);
    void playerSnapshotJsonChanged();
    void playerTracksJsonChanged();
    void pendingPlayerActionChanged();

private:
    QStringList m_pendingPlayerActions;
    QString m_playerSnapshotJson;
    QString m_playerTracksJson;
    quint64 m_nextPlayerActionId = 1;
};
