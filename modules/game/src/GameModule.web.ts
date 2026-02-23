import { registerWebModule, NativeModule } from 'expo';

import { ChangeEventPayload } from './Game.types';

type GameModuleEvents = {
  onChange: (params: ChangeEventPayload) => void;
}

class GameModule extends NativeModule<GameModuleEvents> {
  PI = Math.PI;
  async setValueAsync(value: string): Promise<void> {
    this.emit('onChange', { value });
  }
  hello() {
    return 'Hello world! 👋';
  }
};

export default registerWebModule(GameModule, 'GameModule');
