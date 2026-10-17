@import campfire.users.User
@import campfire.view.*
@param View v
@param java.util.List<User> users
@param String nextPage
<turbo-stream action="replace" target="next_page_container"><template>@for(User user : users)@template.accounts.users._user(v = v, user = user)@endfor</template></turbo-stream>

@if(nextPage != null)<turbo-stream action="append" target="account_users"><template>@template.accounts.users._next_page_container(page = nextPage)</template></turbo-stream>
@endif