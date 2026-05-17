#include "native_player_bridge.h"

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
