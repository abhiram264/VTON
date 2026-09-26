import { useCallback, useEffect, useRef, useState } from 'react';
import {
  NavigationContainer,
  StackActions,
  createNavigationContainerRef,
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
type PendingDeepLink =
  | { name: 'Look'; params: RootStackParamList['Look'] }
  | { name: 'ShareImport'; params: RootStackParamList['ShareImport'] };



export default function AppShell() {
  const navigationRef = useRef(createNavigationContainerRef<RootStackParamList>()).current;
  const [pendingDeepLinks, setPendingDeepLinks] = useState<PendingDeepLink[]>([]);
  const navigateTarget = useCallback((target: PendingDeepLink) => {
    navigationRef.dispatch(StackActions.push(target.name, target.params));
  }, [navigationRef]);
  const navigateOrQueue = useCallback((target: PendingDeepLink) => {
    if (navigationRef.isReady()) {
      navigateTarget(target);
      return;
    }
    setPendingDeepLinks(current => [...current, target]);
  }, [navigateTarget, navigationRef]);

  useEffect(() => {
    if (pendingDeepLinks.length === 0) return;
    let cancelled = false;
    let attempts = 0;
    let timer: number | undefined;
    const drain = () => {
      if (cancelled) return;
      if (!navigationRef.isReady()) {
        if (attempts < 20) {
          attempts += 1;
          timer = setTimeout(drain, 0);
        }
        return;
      }
      const targets = pendingDeepLinks;
      setPendingDeepLinks([]);
      targets.forEach(navigateTarget);
    };
    timer = setTimeout(drain, 0);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [navigateTarget, navigationRef, pendingDeepLinks]);

  useEffect(
    () =>
      startShareLinkHandling(
        (name, params) => navigateOrQueue({ name, params }),
        (name, params) => navigateOrQueue({ name, params }),
      ),
    [navigateOrQueue],
  );

  return (
    <SafeAreaProvider initialMetrics={initialMetrics}>
      <NavigationContainer
        ref={navigationRef}
        onReady={() => {
          setPendingDeepLinks(current => [...current]);
        }}
      >
        <RootNavigator />
      </NavigationContainer>
    </SafeAreaProvider>
  );
}
