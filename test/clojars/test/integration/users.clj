(ns clojars.test.integration.users
  (:require [clojure.test :refer :all]
            [kerodon.core :refer :all]
            [kerodon.test :refer :all]
            [clojars.test.integration.steps :refer :all]
            [clojars.web :as web]
            [clojars.test.test-helper :as help]
            [net.cgrand.enlive-html :as enlive]))

(use-fixtures :each help/default-fixture)

(deftest user-can-register
  (-> (session web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" "")
      (follow-redirect)
      (has (status? 200))
      (within [:div.light-article :> :h1]
              (has (text? "Dashboard (dantheman)")))))

(deftest bad-registration-info-should-show-error
  (-> (session web/clojars-app)
      (register-as "fixture" "fixture@example.org" "password" ""))
  (-> (session web/clojars-app)
      (visit "/")
      (follow "register")
      (has (status? 200))
      (within [:title]
              (has (text? "Register - Clojars")))

      (fill-in "Email" "test@example.org")
      (fill-in "Username" "dantheman")
      (press "Register")
      (has (status? 200))
      (within [:div.error :ul :li]
              (has (text? "Password can't be blank")))

      (fill-in "Password" "password")
      (fill-in "Email" "test@example.com")
      (fill-in "Username" "dantheman")
      (press "Register")
      (has (status? 200))
      (within [:div.error :ul :li]
              (has (text? "Password and confirm password must match")))

      (fill-in "Email" "")
      (fill-in "Username" "dantheman")
      (fill-in "Password" "password")
      (fill-in "Confirm password" "password")
      (press "Register")
      (has (status? 200))
      (within [:div.error :ul :li]
              (has (text? "Email can't be blank")))

      (fill-in "Email" "test@example.org")
      (fill-in "Username" "")
      (fill-in "Password" "password")
      (fill-in "Confirm password" "password")
      (press "Register")
      (has (status? 200))
      (within [:div.error :ul :li]
              (has (text? "Username must consist only of lowercase letters, numbers, hyphens and underscores.Username can't be blank")))
      (fill-in "Username" "<script>")
      (fill-in "Password" "password")
      (fill-in "Confirm password" "password")
      (press "Register")
      (has (status? 200))
      (within [:div.error :ul :li]
              (has (text? "Username must consist only of lowercase letters, numbers, hyphens and underscores.")))

      (fill-in "Username" "fixture")
      (fill-in "Password" "password")
      (fill-in "Confirm password" "password")
      (press "Register")
      (has (status? 200))
      (within [:div.error :ul :li]
              (has (text? "Username is already taken")))

      (fill-in "Username" "dantheman")
      (fill-in "Password" "password")
      (fill-in "Confirm password" "password")
      (fill-in "SSH public key" "asdf")
      (press "Register")
      (has (status? 200))
      (within [:div.error :ul :li]
              (has (text? "Invalid SSH public key")))))

(deftest user-can-update-info
  (-> (session web/clojars-app)
      (register-as "fixture" "fixture@example.org" "password" "")
      (follow-redirect)
      (follow "profile")
      (fill-in "Email" "fixture2@example.org")
      (fill-in "Password" "password2")
      (fill-in "Confirm password" "password2")
      (press "Update")
      (follow-redirect)
      (within [:div#flash]
              (has (text? "Profile updated.")))
      (follow "logout")
      (follow-redirect)
      (has (status? 200))
      (within [:nav [:li enlive/first-child] :a]
              (has (text? "login")))
      (login-as "fixture2@example.org" "password2")
      (follow-redirect)
      (has (status? 200))
      (within [:div.light-article :> :h1]
              (has (text? "Dashboard (fixture)")))))

(deftest user-can-update-just-ssh-key
  (-> (session web/clojars-app)
      (register-as "fixture" "fixture@example.org" "password" "")
      (follow-redirect)
      (follow "profile")
      (fill-in "SSH public key" "ssh-rsa AAAAB3Nza")
      (press "Update")
      (follow-redirect)
      (within [:textarea]
              (has (text? "ssh-rsa AAAAB3Nza")))
      (follow "logout")
      (follow-redirect)
      (login-as "fixture@example.org" "password")
      (follow-redirect)
      (has (status? 200))
      (within [:div.light-article :> :h1]
              (has (text? "Dashboard (fixture)")))))

(deftest bad-update-info-should-show-error
  (-> (session web/clojars-app)
      (register-as "fixture" "fixture@example.org" "password" "")
      (follow-redirect)
      (follow "profile")
      (has (status? 200))
      (within [:title]
              (has (text? "Profile - Clojars")))

      (fill-in "Password" "password")
      (press "Update")
      (has (status? 200))
      (within [:div.error :ul :li]
              (has (text? "Password and confirm password must match")))

      (fill-in "Email" "")
      (press "Update")
      (has (status? 200))
      (within [:div.error :ul :li]
              (has (text? "Email can't be blank")))

      (fill-in "SSH public key" "asdf")
      (press "Update")
      (has (status? 200))
      (within [:div.error :ul :li]
              (has (text? "Invalid SSH public key")))))

(deftest user-can-get-new-password
  (let [transport (promise)]
    (with-redefs [clojars.web.user/send-out (fn [x] (deliver transport x))]
      (-> (session web/clojars-app)
          (register-as "fixture" "fixture@example.org" "password" ""))
      (-> (session web/clojars-app)
          (visit "/")
          (follow "login")
          (follow "Forgot password?")
          (fill-in "Email or Username" "fixture")
          (press "Send new password")
          (has (status? 200))
          (within [:p]
                  (has (text? "If your account was found, you should get an email with a new password soon."))))
      (let [email (deref transport 100 nil)]
        (is email)
        (let [from (.getFromAddress email)]
          (is (= (.getAddress from) "noreply@clojars.org"))
          (is (= (.getPersonal from) "Clojars")))
        (let [to (first (.getToAddresses email))]
          (is (= (.getAddress to) "fixture@example.org")))
        (is (= (.getSubject email)
               "Password reset for Clojars"))
        (.buildMimeMessage email)
        (let [[msg password] (re-find
                              #"Hello,\n\nYour new password for Clojars is: ([^ ]+)\n\nKeep it safe this time."
                              (.getContent (.getMimeMessage email)))]
          (-> (session web/clojars-app)
              (login-as "fixture@example.org" password)
              (follow-redirect)
              (has (status? 200))
              (within [:div.light-article :> :h1]
                      (has (text? "Dashboard (fixture)")))))))))

(deftest member-can-add-user-to-group
  (-> (session web/clojars-app)
      (register-as "fixture" "fixture@example.org" "password" ""))
  (-> (session web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" "")
      (visit "/groups/org.clojars.dantheman")
      (fill-in [:#username] "fixture")
      (press "add member")
      ;;(follow-redirect)
      (within [:div.small-section :ul]
              (has (text? "danthemanfixture")))))

(deftest user-must-exist-to-be-added-to-group
  (-> (session web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" "")
      (visit "/groups/org.clojars.dantheman")
      (fill-in [:#username] "fixture")
      (press "add member")
      (within [:div.error :ul :li]
              (has (text? "No such user: fixture")))))

(deftest users-can-be-viewed
  (-> (session web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" "")
      (visit "/users/dantheman")
      (within [:div.light-article :> :h1]
              (has (text? "dantheman")))))
