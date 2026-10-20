import { ReasonDialog } from './ReasonDialog';

type ReasonDialogProps = Parameters<typeof ReasonDialog>[0];

/** Asks the admin why a recipe is rejected. */
export function RejectDialog(props: Omit<ReasonDialogProps, 'title' | 'description' | 'confirmLabel'>) {
  return (
    <ReasonDialog
      {...props}
      title="Reject recipe?"
      description="The author will see this reason and can edit the recipe to send it back for review."
      confirmLabel="Reject recipe"
    />
  );
}
