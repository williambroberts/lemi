import { requireNativeView } from "expo";
import * as React from "react";

import { GameViewProps } from "./Game.types";

const NativeView: React.ComponentType<GameViewProps> =
  requireNativeView("Game");

export default function GameView(props: GameViewProps) {
  return <NativeView {...props} />;
}
