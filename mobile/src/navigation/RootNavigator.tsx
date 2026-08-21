import { createNativeStackNavigator } from '@react-navigation/native-stack';
import StartupScreen from '../screens/StartupScreen';

export type RootStackParamList = {
  Startup: undefined;
};

const Stack = createNativeStackNavigator<RootStackParamList>();

export default function RootNavigator() {
  return (
    <Stack.Navigator>
      <Stack.Screen name="Startup" component={StartupScreen} />
    </Stack.Navigator>
  );
}
