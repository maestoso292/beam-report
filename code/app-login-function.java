private void login(){
    String username = emailEditText.getText().toString().trim();
    String password = passwordEditText.getText().toString().trim();
    if(TextUtils.isEmpty(username)  TextUtils.isEmpty(password)) {
        Toast.makeText(getContext(), Email and password required, Toast.LENGTH_SHORT).show();
    }
    else {
        mAuth.signInWithEmailAndPassword(username, password).addOnCompleteListener(new OnCompleteListenerAuthResult() {
            @Override
            public void onComplete(@NonNull TaskAuthResult task) {
                if (task.isSuccessful()) {
                    NavHostFragment.findNavController(LoginFragment.this).popBackStack();
                }
                else {
                    Toast.makeText(getContext(), Error  + task.getException(),Toast.LENGTH_SHORT).show();
                }
            }
        });
    }
}
