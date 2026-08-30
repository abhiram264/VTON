import { createNativeStackNavigator } from '@react-navigation/native-stack';
import GenerateScreen from '../screens/GenerateScreen';
import LookScreen from '../screens/LookScreen';
import MyModelScreen from '../screens/MyModelScreen';
import ShareImportScreen from '../screens/ShareImportScreen';
import StartupScreen from '../screens/StartupScreen';
import type { SharePayload } from '../features/share/types';

export type RootStackParamList = {
  Startup: undefined;
  MyModel: undefined;
  Generate: undefined;
  Look: { lookId: string };
  ShareImport: { payload: SharePayload };
};

const Stack = createNativeStackNavigator<RootStackParamList>();

export default function RootNavigator() {
  return (
    <Stack.Navigator>
      <Stack.Screen name="Startup" component={StartupScreen} />
      <Stack.Screen name="MyModel">
        {({ navigation }) => (
          <MyModelScreen onCreateLook={() => navigation.navigate('Generate')} />
        )}
      </Stack.Screen>
      <Stack.Screen name="Generate">
        {({ navigation }) => (
          <GenerateScreen
            onCreateLook={lookId => navigation.navigate('Look', { lookId })}
            onMissingModel={() => navigation.navigate('MyModel')}
          />
        )}
      </Stack.Screen>
      <Stack.Screen name="Look">
        {({ route }) => <LookScreen lookId={route.params.lookId} />}
      </Stack.Screen>
      <Stack.Screen name="ShareImport">
        {({ route }) => <ShareImportScreen payload={route.params.payload} />}
      </Stack.Screen>
    </Stack.Navigator>
  );
}
