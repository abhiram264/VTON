import { createBottomTabNavigator, type BottomTabBarButtonProps, type BottomTabScreenProps } from '@react-navigation/bottom-tabs';
import type { CompositeScreenProps } from '@react-navigation/native';
import { createNativeStackNavigator, type NativeStackScreenProps } from '@react-navigation/native-stack';
import { Pressable, Text, View } from 'react-native';
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
  return <Pressable {...props} accessibilityRole="tab" accessibilityLabel={label} accessibilityState={{ ...props.accessibilityState, selected: props.accessibilityState?.selected ?? props['aria-selected'] }} />;
}

function MainTabs() {
  return (
    <Tabs.Navigator initialRouteName="HomeTab" screenOptions={({ route }) => ({ headerShown: false, tabBarButton: props => <TabButton {...props} label={PRIMARY_TABS.find(tab => tab.route === route.name)?.label ?? route.name} /> })}>
      <Tabs.Screen name="HomeTab" options={{ tabBarLabel: 'HOME' }}>
        {({ navigation }: TabProps<'HomeTab'>) => <HomeScreen onImport={payload => navigation.navigate('ShareImport', { payload })} onOpenLook={lookId => navigation.navigate('Look', { lookId })} onProfile={() => navigation.navigate('ProfileSettings')} onQuickTry={() => navigation.navigate('Generate')} />}
      </Tabs.Screen>
      <Tabs.Screen name="VaultTab" options={{ tabBarLabel: 'VAULT' }}>
        {({ navigation, route }: TabProps<'VaultTab'>) => <VaultScreen onProfile={() => navigation.navigate('ProfileSettings')} selectionUnavailable={route.params?.mode === 'SELECT_FOR_STUDIO' && !route.params.studioSessionId} onSelect={route.params?.studioSessionId ? itemId => navigation.navigate('StudioTab', { studioSessionId: route.params?.studioSessionId, selectedVaultItemId: itemId }) : undefined} />}
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

export default function RootNavigator() {
  return (
    <Stack.Navigator initialRouteName="MainTabs">
      <Stack.Screen name="MainTabs" component={MainTabs} options={{ headerShown: false }} />
      <Stack.Screen name="ProfileSettings" component={ProfileSettings} options={{ title: 'Profile and settings', headerShown: false }} />
      <Stack.Screen name="MyModel">
        {({ navigation }) => <MyModelScreen onCreateLook={() => navigation.navigate('Generate')} />}
      </Stack.Screen>
      <Stack.Screen name="Generate">
        {({ navigation }) => <GenerateScreen onCreateLook={lookId => navigation.navigate('Look', { lookId })} onMissingModel={() => navigation.navigate('MyModel')} />}
      </Stack.Screen>
      <Stack.Screen name="Look">{({ route }) => <LookScreen lookId={route.params.lookId} />}</Stack.Screen>
      <Stack.Screen name="ShareImport">{({ route }) => <ShareImportScreen payload={route.params.payload} />}</Stack.Screen>
    </Stack.Navigator>
  );
}
