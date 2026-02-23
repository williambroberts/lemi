// Reexport the native module. On web, it will be resolved to GameModule.web.ts
// and on native platforms to GameModule.ts
export { default } from './src/GameModule';
export { default as GameView } from './src/GameView';
export * from  './src/Game.types';
