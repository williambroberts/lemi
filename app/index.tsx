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
//if tile is removed and its the bottom then automatically add a new one of the same tipe to h-1 so there is still ground
//inventory depletion/lemit
//tile destroy leaves a little body to pick up by the player going onto that tile, inventory increased, fn depends on type destroyed
//tree, inventory plant seed, afer X frames becomes Y then Z (full grown)
