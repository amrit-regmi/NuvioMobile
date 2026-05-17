#pragma once

#include <QObject>
#include <QString>

class NativePlayerBridge final : public QObject
{
    Q_OBJECT

public:
    explicit NativePlayerBridge(QObject *parent = nullptr);

    Q_INVOKABLE void playUrl(const QString &url, const QString &headersJson, double startPositionMs);
    Q_INVOKABLE void pause();
    Q_INVOKABLE void resume();
    Q_INVOKABLE void stop();
    Q_INVOKABLE void seekTo(double positionMs);
    Q_INVOKABLE void seekBy(double offsetMs);

signals:
    void playRequested(const QString &url, const QString &headersJson, qint64 startPositionMs);
    void pauseRequested();
    void resumeRequested();
    void stopRequested();
    void seekToRequested(qint64 positionMs);
    void seekByRequested(qint64 offsetMs);
};
