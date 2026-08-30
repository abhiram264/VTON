import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { Pressable, Text, View } from 'react-native';
import type { RootStackParamList } from '../navigation/RootNavigator';

export default function StartupScreen({
  navigation,
}: NativeStackScreenProps<RootStackParamList, 'Startup'>) {
  return (
    <View>
      <Text>Application ready</Text>
      <Pressable
        accessibilityRole="button"
        onPress={() => navigation.navigate('MyModel')}
      >
        <Text>My Model</Text>
      </Pressable>
    </View>
  );
}
