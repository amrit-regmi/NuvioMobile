#pragma once

#include <QWidget>

class MpvRenderWidget;
class PlayerOverlayController;

class PlayerSurfaceWidget final : public QWidget
{
    Q_OBJECT

public:
    explicit PlayerSurfaceWidget(MpvRenderWidget *playerWindow, PlayerOverlayController *overlayController, QWidget *parent = nullptr);
};
