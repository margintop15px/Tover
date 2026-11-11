(ns tover.accounts.persistence
  "Bindings to Tover's existing account tables."
  (:require
    [tover.accounts.profile.model :as profile]))


(def tables
  {::profile/profiles {:table   :public.profiles
                       :columns {:user-id           :user_id
                                 :display-name      :display_name
                                 :language          :language
                                 :name-revision     :name_revision
                                 :language-revision :language_revision}}})
