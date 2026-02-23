import { StyleProp, ViewStyle } from "react-native";

export type OnLoadEventPayload = {
  url: string;
};

export type GameModuleEvents = {
  onChange: (params: ChangeEventPayload) => void;
};

export type ChangeEventPayload = {
  value: string;
};

export type GameViewProps = {
  style?: StyleProp<ViewStyle>;
};
