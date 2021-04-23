MaterialButton logoutButton = view.findViewById(R.id.settings_button_logout);
logoutButton.setOnClickListener(new View.OnClickListener() {
    @Override
    public void onClick(View view) {
        FirebaseAuth.getInstance().signOut();
        NavHostFragment.findNavController(SettingsFragment.this).popBackStack();
    }
});
