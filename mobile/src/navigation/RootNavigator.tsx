import { createBottomTabNavigator, type BottomTabBarButtonProps, type BottomTabScreenProps } from '@react-navigation/bottom-tabs';
import { useFocusEffect, type CompositeScreenProps } from '@react-navigation/native';
import { createNativeStackNavigator, type NativeStackScreenProps } from '@react-navigation/native-stack';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useEffect, useState, type ReactNode } from 'react';
import { subscribeToAuthSession, type AuthSession } from '../config/firebase';
import SignInScreen from '../screens/SignInScreen';
import GenerateScreen from '../screens/GenerateScreen';
import HomeScreen from '../screens/HomeScreen';
import LookScreen from '../screens/LookScreen';
import LooksScreen from '../screens/LooksScreen';
import MyModelScreen from '../screens/MyModelScreen';
import ShareImportScreen from '../screens/ShareImportScreen';
import StudioScreen from '../screens/StudioScreen';
import VaultScreen from '../screens/VaultScreen';
import type { SharePayload } from '../features/share/types';

export type PrimaryTabParamList = {
  HomeTab: undefined;
  VaultTab: { mode?: 'BROWSE' | 'SELECT_FOR_STUDIO'; studioSessionId?: string } | undefined;
  StudioTab: { studioSessionId?: string; sourceLookId?: string; selectedVaultItemId?: string } | undefined;
  LooksTab: undefined;
};

export type RootStackParamList = {
  MainTabs: { screen?: keyof PrimaryTabParamList; params?: PrimaryTabParamList[keyof PrimaryTabParamList] } | undefined;
  ProfileSettings: undefined;
  MyModel: undefined;
  Generate: undefined;
  Look: { lookId: string };
  ShareImport: { payload: SharePayload };
};

export const PRIMARY_TABS = [
  { route: 'HomeTab', label: 'HOME' },
  { route: 'VaultTab', label: 'VAULT' },
  { route: 'StudioTab', label: 'STUDIO' },
  { route: 'LooksTab', label: 'LOOKS' },
] as const;

export type VaultFilter = 'ALL' | 'TOPS' | 'BOTTOMS' | 'ONE_PIECES' | 'ETHNIC' | 'FOOTWEAR' | 'HEADWEAR' | 'ACCESSORIES' | 'COLLECTIONS';

type TabProps<T extends keyof PrimaryTabParamList> = CompositeScreenProps<BottomTabScreenProps<PrimaryTabParamList, T>, NativeStackScreenProps<RootStackParamList>>;

const Stack = createNativeStackNavigator<RootStackParamList>();
const Tabs = createBottomTabNavigator<PrimaryTabParamList>();

function TabButton({ label, accessibilityRole: _role, role: _ariaRole, ...props }: BottomTabBarButtonProps & { label: string }) {
  return <Pressable {...props} accessibilityRole="tab" accessibilityLabel={props.accessibilityLabel ?? label} accessibilityState={{ ...props.accessibilityState, selected: props.accessibilityState?.selected ?? props['aria-selected'] }} />;
}

function VaultTabScreen({ navigation, route }: TabProps<'VaultTab'>) {
  useFocusEffect(() => () => {
    navigation.setParams({ mode: 'BROWSE', studioSessionId: undefined });
  });
  return <VaultScreen onProfile={() => navigation.navigate('ProfileSettings')} selectionUnavailable={route.params?.mode === 'SELECT_FOR_STUDIO' && !route.params.studioSessionId} onSelect={route.params?.studioSessionId ? itemId => { navigation.setParams({ mode: 'BROWSE', studioSessionId: undefined }); navigation.navigate('StudioTab', { studioSessionId: route.params?.studioSessionId, selectedVaultItemId: itemId }); } : undefined} />;
}

function MainTabs() {
  return (
    <Tabs.Navigator initialRouteName="HomeTab" screenOptions={({ route }) => ({ headerShown: false, tabBarButton: props => <TabButton {...props} label={PRIMARY_TABS.find(tab => tab.route === route.name)?.label ?? route.name} /> })}>
      <Tabs.Screen name="HomeTab" options={{ tabBarLabel: 'HOME' }}>
        {({ navigation }: TabProps<'HomeTab'>) => <HomeScreen onImport={payload => navigation.navigate('ShareImport', { payload })} onOpenLook={lookId => navigation.navigate('Look', { lookId })} onProfile={() => navigation.navigate('ProfileSettings')} onQuickTry={() => navigation.navigate('Generate')} />}
      </Tabs.Screen>
      <Tabs.Screen name="VaultTab" options={{ tabBarLabel: 'VAULT' }}>
        {props => <VaultTabScreen {...props} />}
      </Tabs.Screen>
      <Tabs.Screen name="StudioTab" options={{ tabBarLabel: 'STUDIO' }}>
        {({ navigation, route }: TabProps<'StudioTab'>) => <StudioScreen {...route.params} onAddFromVault={studioSessionId => navigation.navigate('VaultTab', { mode: 'SELECT_FOR_STUDIO', studioSessionId })} onAvatar={() => navigation.navigate('MyModel')} onGenerate={() => navigation.navigate('Generate')} onProfile={() => navigation.navigate('ProfileSettings')} />}
      </Tabs.Screen>
      <Tabs.Screen name="LooksTab" options={{ tabBarLabel: 'LOOKS' }}>
        {({ navigation }: TabProps<'LooksTab'>) => <LooksScreen onModify={sourceLookId => navigation.navigate('StudioTab', { sourceLookId })} onOpenLook={lookId => navigation.navigate('Look', { lookId })} onProfile={() => navigation.navigate('ProfileSettings')} />}
      </Tabs.Screen>
    </Tabs.Navigator>
  );
}
function ProfileSettings({ navigation }: NativeStackScreenProps<RootStackParamList, 'ProfileSettings'>) {
  return <View><Pressable accessibilityRole="button" accessibilityLabel="Back" onPress={navigation.goBack}><Text>Back</Text></Pressable><MyModelScreen onCreateLook={() => navigation.navigate('Generate')} /></View>;
}

// Leading `loading` is the no-flash contract: a restored session never shows
// the sign-in screen because the first committed state is the holding view.
function useAuthSession(): AuthSession {
  const [session, setSession] = useState<AuthSession>({ status: 'loading' });
  useEffect(() => subscribeToAuthSession(setSession), []);
  return session;
}

// The gate controls WHICH SCREEN IS PRESENTED, never WHICH ROUTES EXIST:
// every route stays registered on the navigation ref so AppShell's by-name
// pushes (`Look`, `ShareImport`) always land, even while signed out.
//
// `signed-out` and `unavailable` are deliberately different states. Only
// `signed-out` walls the app behind sign-in: auth is configured and the user
// can actually pass the wall. An `unavailable` surface means NO auth provider
// is configured at all, so there is nothing to sign in to — walling the app
// behind a screen the user can never pass would make the app permanently
// unusable (and with no env-injection mechanism in this repo, `unavailable`
// is the only state a local run can reach).
//
// SECURITY RATIONALE: passing the app through on `unavailable` is a local-dev
// RENDERING decision only, never an auth bypass. Every API call still fails
// closed through the token seam — apiConfig.getAccessToken throws when the
// surface is unavailable (and when nobody is signed in), so NO request can
// ever leave the app without a token, regardless of which screen is
// presented. rootNavigatorGating.test.tsx pins that rejection so the two
// guarantees cannot drift apart.
function SessionGate({ session, signedIn }: { session: AuthSession; signedIn?: ReactNode }) {
  if (session.status === 'loading') return <View style={styles.holding} testID="auth-holding" />;
  if (session.status === 'signed-out') return <SignInScreen />;
  return <>{signedIn ?? null}</>;
}

export default function RootNavigator({ session: injectedSession }: { session?: AuthSession } = {}) {
  const subscribedSession = useAuthSession();
  const session = injectedSession ?? subscribedSession;
  return (
    <Stack.Navigator initialRouteName="MainTabs">
      <Stack.Screen name="MainTabs" options={{ headerShown: false }}>
        {() => <SessionGate session={session} signedIn={<MainTabs />} />}
      </Stack.Screen>
      <Stack.Screen name="ProfileSettings" component={ProfileSettings} options={{ title: 'Profile and settings', headerShown: false }} />
      <Stack.Screen name="MyModel">
        {({ navigation }) => <MyModelScreen onCreateLook={() => navigation.navigate('Generate')} />}
      </Stack.Screen>
      <Stack.Screen name="Generate">
        {({ navigation }) => <GenerateScreen onCreateLook={lookId => navigation.navigate('Look', { lookId })} onMissingModel={() => navigation.navigate('MyModel')} />}
      </Stack.Screen>
      <Stack.Screen name="Look">
        {({ navigation, route }) => <SessionGate session={session} signedIn={<LookScreen lookId={route.params.lookId} onDeleted={() => navigation.goBack()} />} />}
      </Stack.Screen>
      <Stack.Screen name="ShareImport">
        {({ route }) => <SessionGate session={session} signedIn={<ShareImportScreen payload={route.params.payload} />} />}
      </Stack.Screen>
    </Stack.Navigator>
  );
}

const styles = StyleSheet.create({
  holding: { flex: 1 },
});
