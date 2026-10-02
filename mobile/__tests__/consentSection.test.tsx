import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { Alert } from 'react-native';
import ConsentSection from '../src/features/my-model/ConsentSection';
import { ApiProblemError } from '../src/features/my-model/http';
import {
  CONSENT_POLICY_VERSION,
  type ConsentApi,
  type ConsentRecord,
} from '../src/features/my-model/consentApi';

function record(overrides: Partial<ConsentRecord> = {}): ConsentRecord {
  return {
    purpose: 'third_party_ai_transfer',
    version: CONSENT_POLICY_VERSION,
    grantedAt: '2026-10-01T10:00:00Z',
    revokedAt: null,
    active: true,
    ...overrides,
  };
}

function fakeApi(overrides: Partial<ConsentApi> = {}): ConsentApi {
  return {
    list: jest.fn(async () => []),
    grant: jest.fn(async purpose => record({ purpose })),
    withdraw: jest.fn(async () => undefined),
    ...overrides,
  };
}

describe('ConsentSection', () => {
  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('names every disclosed purpose and shows the policy version', async () => {
    await render(<ConsentSection api={fakeApi()} />);

    await waitFor(() =>
      expect(screen.getByText(/Policy version/)).toBeTruthy(),
    );
    expect(screen.getByText('Process my body photo')).toBeTruthy();
    expect(screen.getByText('Send my photo to the AI provider')).toBeTruthy();
    expect(screen.getByText('Keep my photo and results')).toBeTruthy();
    expect(screen.getAllByText('Not granted — new Looks needing this are blocked')).toHaveLength(3);
  });

  it('grants a purpose and re-reads the authoritative state from the server', async () => {
    const api = fakeApi({
      list: jest
        .fn()
        .mockResolvedValueOnce([])
        .mockResolvedValueOnce([record({ purpose: 'retention' })]),
    });
    await render(<ConsentSection api={api} />);
    await waitFor(() => expect(api.list).toHaveBeenCalledTimes(1));

    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Grant: Keep my photo and results' }));
    });

    expect(api.grant).toHaveBeenCalledWith('retention', CONSENT_POLICY_VERSION);
    expect(api.list).toHaveBeenCalledTimes(2);
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: 'Withdraw: Keep my photo and results' }),
      ).toBeTruthy(),
    );
  });

  it('requires confirmation before withdrawing and sends nothing when cancelled', async () => {
    const api = fakeApi({
      list: jest.fn(async () => [record({ purpose: 'retention' })]),
    });
    const alert = jest.spyOn(Alert, 'alert').mockImplementation(() => undefined);
    await render(<ConsentSection api={api} />);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Withdraw: Keep my photo and results' })).toBeTruthy(),
    );

    fireEvent.press(screen.getByRole('button', { name: 'Withdraw: Keep my photo and results' }));

    expect(alert).toHaveBeenCalled();
    expect(api.withdraw).not.toHaveBeenCalled();
  });

  it('withdraws after confirmation and shows the withdrawn state', async () => {
    const api = fakeApi({
      list: jest
        .fn()
        .mockResolvedValueOnce([record({ purpose: 'retention' })])
        .mockResolvedValueOnce([
          record({
            purpose: 'retention',
            active: false,
            revokedAt: '2026-10-02T10:00:00Z',
          }),
        ]),
    });
    jest
      .spyOn(Alert, 'alert')
      .mockImplementation((_title, _body, buttons) => {
        const confirm = buttons?.find(button => button.text === 'Withdraw');
        confirm?.onPress?.();
      });
    await render(<ConsentSection api={api} />);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Withdraw: Keep my photo and results' })).toBeTruthy(),
    );

    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Withdraw: Keep my photo and results' }));
    });

    expect(api.withdraw).toHaveBeenCalledWith('retention');
    await waitFor(() =>
      expect(screen.getByText(/^Withdrawn/)).toBeTruthy(),
    );
  });

  it('reports a refused withdrawal as a real answer rather than pretending it succeeded', async () => {
    const api = fakeApi({
      list: jest
        .fn()
        .mockResolvedValueOnce([record({ purpose: 'retention' })])
        .mockResolvedValueOnce([record({ purpose: 'retention' })]),
      withdraw: jest.fn(async () => {
        throw new ApiProblemError('CONSENT_NOT_GRANTED');
      }),
    });
    jest
      .spyOn(Alert, 'alert')
      .mockImplementation((_title, _body, buttons) => {
        buttons?.find(button => button.text === 'Withdraw')?.onPress?.();
      });
    await render(<ConsentSection api={api} />);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Withdraw: Keep my photo and results' })).toBeTruthy(),
    );

    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Withdraw: Keep my photo and results' }));
    });

    await waitFor(() =>
      expect(
        screen.getByText('That consent was not active, so nothing was withdrawn.'),
      ).toBeTruthy(),
    );
    // The state must not be locally flipped to "Withdrawn" on a refused request.
    expect(screen.getByRole('button', { name: 'Withdraw: Keep my photo and results' })).toBeTruthy();
  });

  it('offers a retry when consent state cannot be loaded and does not claim a state', async () => {
    const api = fakeApi({
      list: jest.fn(async () => {
        throw new Error('offline');
      }),
    });
    await render(<ConsentSection api={api} />);

    await waitFor(() =>
      expect(screen.getByText('Consent settings could not be loaded.')).toBeTruthy(),
    );
    expect(screen.queryByText('Granted')).toBeNull();
    expect(screen.getByRole('button', { name: 'Retry consent settings' })).toBeTruthy();
  });
});
