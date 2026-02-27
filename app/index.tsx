import { GameView } from "@/modules/game";
import { SafeAreaView } from "react-native-safe-area-context";

export default function Index() {
  // useEffect(() => {
  //   ScreenOrientation.lockAsync(ScreenOrientation.OrientationLock.LANDSCAPE);
  //   return () => {
  //     ScreenOrientation.unlockAsync();
  //   };
  // }, []);
  return (
    <SafeAreaView
      style={{
        flex: 1,
        justifyContent: "center",
        alignItems: "center",
      }}
    >
      <GameView style={{ flex: 1, backgroundColor: "red", width: "100%" }} />
    </SafeAreaView>
  );
}
