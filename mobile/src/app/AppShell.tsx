import { useEffect } from 'react';
import {
  NavigationContainer,
  useNavigationContainerRef,
} from '@react-navigation/native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import RootNavigator, {
  type RootStackParamList,
} from '../navigation/RootNavigator';
import { startShareLinkHandling } from '../features/share/linkHandler';

const initialMetrics = {
  frame: { x: 0, y: 0, width: 0, height: 0 },
  insets: { top: 0, left: 0, right: 0, bottom: 0 },
};

export default function AppShell() {
  const navigationRef = useNavigationContainerRef<RootStackParamList>();

  useEffect(
    () =>
      startShareLinkHandling(
        (name, params) => {
          if (navigationRef.isReady()) {
            navigationRef.navigate(name, params);
          }
        },
        (name, params) => {
          if (navigationRef.isReady()) {
            navigationRef.navigate(name, params);
          }
        },
      ),
    [navigationRef],
  );

  return (
    <SafeAreaProvider initialMetrics={initialMetrics}>
      <NavigationContainer ref={navigationRef}>
        <RootNavigator />
      </NavigationContainer>
    </SafeAreaProvider>
  );
}
