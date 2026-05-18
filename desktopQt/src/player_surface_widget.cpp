#include "player_surface_widget.h"

#include "mpv_render_widget.h"
#include "player_overlay_controller.h"

#include <QQuickWidget>
#include <QQmlContext>
#include <QStackedLayout>
#include <QUrl>

PlayerSurfaceWidget::PlayerSurfaceWidget(
    MpvRenderWidget *playerWindow,
    PlayerOverlayController *overlayController,
    QWidget *parent
)
    : QWidget(parent)
{
    setAttribute(Qt::WA_StyledBackground, true);
    setStyleSheet(QStringLiteral("background: black;"));

    auto *layout = new QStackedLayout(this);
    layout->setContentsMargins(0, 0, 0, 0);
    layout->setStackingMode(QStackedLayout::StackAll);

    playerWindow->setParent(this);
    playerWindow->setFocusPolicy(Qt::StrongFocus);
    layout->addWidget(playerWindow);

    auto *overlay = new QQuickWidget(this);
    overlay->setResizeMode(QQuickWidget::SizeRootObjectToView);
    overlay->setClearColor(Qt::transparent);
    overlay->setAttribute(Qt::WA_AlwaysStackOnTop);
    overlay->setAttribute(Qt::WA_TranslucentBackground);
    overlay->rootContext()->setContextProperty(QStringLiteral("playerOverlay"), overlayController);
    overlay->setSource(QUrl(QStringLiteral("qrc:/qml/PlayerOverlay.qml")));
    layout->addWidget(overlay);
    layout->setCurrentWidget(overlay);
    overlay->raise();
}
