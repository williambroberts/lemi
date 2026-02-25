import { GameView } from "@/modules/game";
import { View } from "react-native";

export default function Index() {
  // useEffect(() => {
  //   ScreenOrientation.lockAsync(ScreenOrientation.OrientationLock.LANDSCAPE);
  //   return () => {
  //     ScreenOrientation.unlockAsync();
  //   };
  // }, []);
  return (
    <View
      style={{
        flex: 1,
        justifyContent: "center",
        alignItems: "center",
      }}
    >
      <GameView style={{ flex: 1, backgroundColor: "red", width: "100%" }} />
    </View>
  );
}
