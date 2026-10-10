import { Component, type ReactNode } from 'react';
import { CircleAlert } from 'lucide-react';
import { EmptyState } from './ui';

export class ErrorBoundary extends Component<{ children: ReactNode }, { hasError: boolean }> {
  state = { hasError: false };

  static getDerivedStateFromError() {
    return { hasError: true };
  }

  render() {
    if (this.state.hasError) {
      return <EmptyState icon={CircleAlert}>Something went wrong rendering this page.</EmptyState>;
    }
    return this.props.children;
  }
}
