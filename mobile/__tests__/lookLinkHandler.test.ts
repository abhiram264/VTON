import {
  extractLookId,
  handleLookLink,
} from '../src/features/share/lookLinkHandler';

// 'Look' is the route really mounted by the root stack navigator
// (mobile/src/navigation/RootNavigator.tsx:89 renders
// <LookScreen lookId={route.params.lookId} />, and RootStackParamList declares
// Look: { lookId: string } at :27), so a handled deep link must arrive there
// carrying the id parsed out of the URI. A route that is not mounted, or an
// id that is dropped, is what these assertions rule out.
const MOUNTED_LOOK_ROUTE = 'Look';

type OpenedLook = { route: string; lookId: string };

function openedLooks(): {
  opened: OpenedLook[];
  navigate: (name: 'Look', params: { lookId: string }) => void;
} {
  const opened: OpenedLook[] = [];
  return {
    opened,
    navigate(name, params) {
      opened.push({ route: name, lookId: params.lookId });
    },
  };
}

describe('look deep links', () => {
  it('opens the mounted Look screen carrying the id from the link', () => {
    // Shape the backend actually emits: Notification.java:81 sends
    // lookId.value().toString() — a lowercase canonical UUID — and iOS
    // ShareHandoffContract.lookURL builds fashionvault://looks/{lookId}.
    const lookId = '3f2504e0-4f89-41d3-9a0c-0305e82c3301';
    const { opened, navigate } = openedLooks();

    handleLookLink(`fashionvault://looks/${lookId}`, navigate);

    expect(opened).toEqual([
      { route: MOUNTED_LOOK_ROUTE, lookId },
    ]);
  });

  it('refuses a malformed id instead of opening the Look screen', () => {
    const malformed = [
      // Missing id.
      'fashionvault://looks/',
      // Not a UUID.
      'fashionvault://looks/not-a-uuid',
      // Uppercase hex: not the canonical lowercase form the backend sends.
      'fashionvault://looks/3F2504E0-4F89-41D3-9A0C-0305E82C3301',
      // Query suffix on an otherwise valid id.
      'fashionvault://looks/3f2504e0-4f89-41d3-9a0c-0305e82c3301?src=push',
      // Extra path segment.
      'fashionvault://looks/3f2504e0-4f89-41d3-9a0c-0305e82c3301/extra',
      // Wrong host on the right scheme.
      'fashionvault://shares/3f2504e0-4f89-41d3-9a0c-0305e82c3301',
      // The share-handoff scheme must never open a Look.
      'aifashionvault://looks/3f2504e0-4f89-41d3-9a0c-0305e82c3301',
    ];
    const { opened, navigate } = openedLooks();

    for (const uri of malformed) {
      expect(extractLookId(uri)).toBeNull();
      handleLookLink(uri, navigate);
    }

    // No malformed URI is allowed to reach the Look screen.
    expect(opened).toEqual([]);
  });
});
