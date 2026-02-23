import * as React from 'react';

import { GameViewProps } from './Game.types';

export default function GameView(props: GameViewProps) {
  return (
    <div>
      <iframe
        style={{ flex: 1 }}
        src={props.url}
        onLoad={() => props.onLoad({ nativeEvent: { url: props.url } })}
      />
    </div>
  );
}
